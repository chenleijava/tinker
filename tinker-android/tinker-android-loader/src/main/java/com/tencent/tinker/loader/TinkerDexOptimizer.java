/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.loader;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.tencent.tinker.loader.shareutil.ShareFileLockHelper;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.ShareReflectUtil;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;
import com.tencent.tinker.loader.shareutil.ShareTinkerLog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;


/**
 * Created by tangyinsheng on 2016/11/15.
 */

public final class TinkerDexOptimizer {
    private static final String TAG = "Tinker.ParallelDex";

    private static final String INTERPRET_LOCK_FILE_NAME = "interpret.lock";

    /**
     * Optimize (trigger dexopt or dex2oat) dexes.
     *
     * @param dexFiles
     * @param optimizedDir
     * @param cb
     * @return If all dexes are optimized successfully, return true. Otherwise return false.
     */
    public static boolean optimizeAll(Context context, Collection<File> dexFiles, File optimizedDir, ResultCallback cb) {
        return optimizeAll(context, dexFiles, optimizedDir, false, null, cb);
    }

    public static boolean optimizeAll(Context context, Collection<File> dexFiles, File optimizedDir,
                                      boolean useInterpretMode, String targetISA, ResultCallback cb) {
        ArrayList<File> sortList = new ArrayList<>(dexFiles);
        // sort input dexFiles with its file length in reverse order.
        Collections.sort(sortList, new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                final long lhsSize = lhs.length();
                final long rhsSize = rhs.length();
                if (lhsSize < rhsSize) {
                    return 1;
                } else if (lhsSize == rhsSize) {
                    return 0;
                } else {
                    return -1;
                }
            }
        });
        for (File dexFile : sortList) {
            OptimizeWorker worker = new OptimizeWorker(context, dexFile, optimizedDir, useInterpretMode, targetISA, cb);
            if (!worker.run()) {
                return false;
            }
        }
        return true;
    }

    public interface ResultCallback {
        void onStart(File dexFile, File optimizedDir);

        void onSuccess(File dexFile, File optimizedDir, File optimizedFile);

        void onFailed(File dexFile, File optimizedDir, Throwable thr);
    }

    private static class OptimizeWorker {
        private static String targetISA = null;
        private final Context        context;
        private final File           dexFile;
        private final File           optimizedDir;
        private final boolean        useInterpretMode;
        private final ResultCallback callback;

        OptimizeWorker(Context context, File dexFile, File optimizedDir, boolean useInterpretMode, String targetISA, ResultCallback cb) {
            this.context = context;
            this.dexFile = dexFile;
            this.optimizedDir = optimizedDir;
            this.useInterpretMode = useInterpretMode;
            this.callback = cb;
            this.targetISA = targetISA;
        }

        boolean run() {
            try {
                if (!SharePatchFileUtil.isLegalFile(dexFile)) {
                    if (callback != null) {
                        callback.onFailed(dexFile, optimizedDir,
                                new IOException("dex file " + dexFile.getAbsolutePath() + " is not exist!"));
                        return false;
                    }
                }
                if (callback != null) {
                    callback.onStart(dexFile, optimizedDir);
                }
                String optimizedPath = SharePatchFileUtil.optimizedPathFor(this.dexFile, this.optimizedDir);
                if (!ShareTinkerInternals.isArkHotRuning()) {
                    if (useInterpretMode) {
                        interpretDex2Oat(dexFile.getAbsolutePath(), optimizedPath);
                    } else if (Build.VERSION.SDK_INT >= 26
                            || (Build.VERSION.SDK_INT >= 25 && Build.VERSION.PREVIEW_SDK_INT != 0)) {
                        NewClassLoaderInjector.triggerDex2Oat(context, optimizedDir, dexFile.getAbsolutePath());
                        // Android Q is significantly slowed down by Fallback Dex Loading procedure, so we
                        // trigger background dexopt to generate executable odex here.
                        triggerPMDexOptOnDemand(context, dexFile.getAbsolutePath(), optimizedPath);
                    } else {
                        DexFile.loadDex(dexFile.getAbsolutePath(), optimizedPath, 0);
                    }
                }
                if (callback != null) {
                    callback.onSuccess(dexFile, optimizedDir, new File(optimizedPath));
                }
            } catch (final Throwable e) {
                ShareTinkerLog.e(TAG, "Failed to optimize dex: " + dexFile.getAbsolutePath(), e);
                if (callback != null) {
                    callback.onFailed(dexFile, optimizedDir, e);
                    return false;
                }
            }
            return true;
        }

        private static void triggerPMDexOptOnDemand(Context context, String dexPath, String oatPath) {
            if (Build.VERSION.SDK_INT != 29) {
                // Only do this trick on Android Q devices.
                ShareTinkerLog.w(TAG, "[+] Not API 29 device, skip fixing.");
                return;
            }
            if (!"huawei".equalsIgnoreCase(Build.MANUFACTURER)) {
                // Only do this trick on huawei devices.
                ShareTinkerLog.w(TAG, "[!] Not Huawei device, skip fixing.");
                return;
            }

            ShareTinkerLog.i(TAG, "[+] Hit target device, do fix logic now.");

            try {
                final File oatFile = new File(oatPath);
                loadDexByPathClassLoader(dexPath);
                if (oatFile.exists()) {
                    ShareTinkerLog.i(TAG, "[+] PathClassLoader generated odex file, skip bg-dexopt triggering.");
                    return;
                }

                try {
                    triggerPMDexOpt(context);
                    if (oatFile.exists()) {
                        ShareTinkerLog.i(TAG, "[+] Bg-dexopt was triggered successfully.");
                        return;
                    } else {
                        throw new IllegalStateException("Bg-dexopt was triggered, but no odex file was generated.");
                    }
                } catch (Throwable thr) {
                    if (!"huawei".equalsIgnoreCase(Build.MANUFACTURER)) {
                        throw thr;
                    }
                }

                triggerPMDexOpt2(context, dexPath);
                if (oatFile.exists()) {
                    ShareTinkerLog.i(TAG, "[+] Bg-dexopt was triggered by registerDexModule successfully.");
                    return;
                } else {
                    throw new IllegalStateException("Bg-dexopt was triggered by registerDexModule, but no odex file was generated.");
                }
            } catch (Throwable thr) {
                ShareTinkerLog.printErrStackTrace(TAG, thr, "[-] Fail to call triggerPMDexOptAsyncOnDemand.");
            }
        }

        private static void loadDexByPathClassLoader(String dexPath) throws IOException {
            ShareTinkerLog.i(TAG, "[+] Load patch by PathClassLoader start.");
            final PathClassLoader cl = new PathClassLoader(dexPath, ClassLoader.getSystemClassLoader());
            ShareTinkerLog.i(TAG, "[+] Load patch by PathClassLoader [%s] done.", cl);
        }

        private static final String PM_INTERFACE_DESCRIPTOR = "android.content.pm.IPackageManager";

        private static void triggerPMDexOpt(Context context) throws IllegalStateException {
            try {
                ShareTinkerLog.i(TAG, "[+] Start trigger secondary dexopt.");
                final int transactionCode = ("xiaomi".equalsIgnoreCase(Build.MANUFACTURER) ? 0x79 : 0x78);
                final String packageName = context.getPackageName();
                final String targetCompilerFilter = "speed";
                final boolean force = true;

                final Class<?> serviceManagerClazz = Class.forName("android.os.ServiceManager");
                final Method getServiceMethod = ShareReflectUtil.findMethod(serviceManagerClazz, "getService", String.class);
                final IBinder pmBinder = (IBinder) getServiceMethod.invoke(null, "package");
                if (pmBinder == null) {
                    throw new IllegalStateException("Fail to get pm binder.");
                }

                try {
                    triggerPMDexOptImpl(pmBinder, transactionCode, packageName, targetCompilerFilter, force);
                } catch (Throwable thr) {
                    // First invocation should always failed.
                    triggerPMDexOptImpl(pmBinder, transactionCode, packageName, targetCompilerFilter, force);
                }
                ShareTinkerLog.i(TAG, "[+] Secondary dexopt done.");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable thr) {
                throw new IllegalStateException("Failure on triggering secondary dexopt", thr);
            }
        }

        private static void triggerPMDexOpt2(Context context, String dexPath) throws IllegalStateException {
            try {
                ShareTinkerLog.i(TAG, "[+] Start trigger secondary dexopt by registerDexModule.");
                final int transactionCode = ("xiaomi".equalsIgnoreCase(Build.MANUFACTURER) ? 0x77 : 0x76);
                final String packageName = context.getPackageName();

                final Class<?> serviceManagerClazz = Class.forName("android.os.ServiceManager");
                final Method getServiceMethod = ShareReflectUtil.findMethod(serviceManagerClazz, "getService", String.class);
                final IBinder pmBinder = (IBinder) getServiceMethod.invoke(null, "package");
                if (pmBinder == null) {
                    throw new IllegalStateException("Fail to get pm binder.");
                }

                try {
                    triggerPMDexOptImpl2(pmBinder, transactionCode, packageName, dexPath);
                } catch (Throwable thr) {
                    // First invocation may failed.
                    triggerPMDexOptImpl2(pmBinder, transactionCode, packageName, dexPath);
                }
                ShareTinkerLog.i(TAG, "[+] Secondary dexopt by registerDexModule done.");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable thr) {
                throw new IllegalStateException("Failure on triggering secondary dexopt by registerDexModule.", thr);
            }
        }

        private static void triggerPMDexOptImpl(IBinder pmBinder, int transactionCode, String packageName, String compileFilter, boolean force) {
            Parcel data = null;
            Parcel reply = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                data = Parcel.obtain();
                reply = Parcel.obtain();
                boolean result;
                data.writeInterfaceToken(PM_INTERFACE_DESCRIPTOR);
                data.writeString(packageName);
                data.writeString(compileFilter);
                data.writeInt(((force) ? (1) : (0)));
                boolean status = false;
                try {
                    status = pmBinder.transact(transactionCode, data, reply, 0);
                    if (!status) {
                        throw new IllegalStateException("Binder transaction failure.");
                    }
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
                try {
                    reply.readException();
                } catch (Throwable thr) {
                    throw new IllegalStateException(thr);
                }
                result = (0 != reply.readInt());
                if (!result) {
                    ShareTinkerLog.w(TAG, "[!] System API return false.");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
                if (reply != null) {
                    reply.recycle();
                }
                if (data != null) {
                    data.recycle();
                }
            }
        }

        private static void triggerPMDexOptImpl2(IBinder pmBinder, int transactionCode, String packageName, String dexPath) {
            Parcel data = null;
            Parcel reply = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                data = Parcel.obtain();
                reply = Parcel.obtain();
                data.writeInterfaceToken(PM_INTERFACE_DESCRIPTOR);
                data.writeString(packageName);
                data.writeString(dexPath);
                data.writeInt(0); // Not a shared module.
                data.writeInt(0); // Callback is null.
                boolean status = false;
                try {
                    status = pmBinder.transact(transactionCode, data, reply, 0);
                    if (!status) {
                        throw new IllegalStateException("Binder transaction failure.");
                    }
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
                try {
                    reply.readException();
                } catch (Throwable thr) {
                    throw new IllegalStateException(thr);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
                if (reply != null) {
                    reply.recycle();
                }
                if (data != null) {
                    data.recycle();
                }
            }
        }

        private void interpretDex2Oat(String dexFilePath, String oatFilePath) throws IOException {
            // add process lock for interpret mode
            final File oatFile = new File(oatFilePath);
            if (!oatFile.exists()) {
                oatFile.getParentFile().mkdirs();
            }

            File lockFile = new File(oatFile.getParentFile(), INTERPRET_LOCK_FILE_NAME);
            ShareFileLockHelper fileLock = null;
            try {
                fileLock = ShareFileLockHelper.getFileLock(lockFile);

                final List<String> commandAndParams = new ArrayList<>();
                commandAndParams.add("dex2oat");
                // for 7.1.1, duplicate class fix
                if (Build.VERSION.SDK_INT >= 24) {
                    commandAndParams.add("--runtime-arg");
                    commandAndParams.add("-classpath");
                    commandAndParams.add("--runtime-arg");
                    commandAndParams.add("&");
                }
                commandAndParams.add("--dex-file=" + dexFilePath);
                commandAndParams.add("--oat-file=" + oatFilePath);
                commandAndParams.add("--instruction-set=" + targetISA);
                if (Build.VERSION.SDK_INT > 25) {
                    commandAndParams.add("--compiler-filter=quicken");
                } else {
                    commandAndParams.add("--compiler-filter=interpret-only");
                }

                final ProcessBuilder pb = new ProcessBuilder(commandAndParams);
                pb.redirectErrorStream(true);
                final Process dex2oatProcess = pb.start();
                StreamConsumer.consumeInputStream(dex2oatProcess.getInputStream());
                StreamConsumer.consumeInputStream(dex2oatProcess.getErrorStream());
                try {
                    final int ret = dex2oatProcess.waitFor();
                    if (ret != 0) {
                        throw new IOException("dex2oat works unsuccessfully, exit code: " + ret);
                    }
                } catch (InterruptedException e) {
                    throw new IOException("dex2oat is interrupted, msg: " + e.getMessage(), e);
                }
            } finally {
                try {
                    if (fileLock != null) {
                        fileLock.close();
                    }
                } catch (IOException e) {
                    ShareTinkerLog.w(TAG, "release interpret Lock error", e);
                }
            }
        }
    }

    private static class StreamConsumer {
        static final Executor STREAM_CONSUMER = Executors.newSingleThreadExecutor();

        static void consumeInputStream(final InputStream is) {
            STREAM_CONSUMER.execute(new Runnable() {
                @Override
                public void run() {
                    if (is == null) {
                        return;
                    }
                    final byte[] buffer = new byte[256];
                    try {
                        while ((is.read(buffer)) > 0) {
                            // To satisfy checkstyle rules.
                        }
                    } catch (IOException ignored) {
                        // Ignored.
                    } finally {
                        try {
                            is.close();
                        } catch (Exception ignored) {
                            // Ignored.
                        }
                    }
                }
            });
        }
    }
}
