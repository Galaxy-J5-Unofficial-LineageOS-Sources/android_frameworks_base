/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.companion.virtual;


import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Set;

/**
 * Handles blocking access to the camera for apps running on virtual devices.
 */
class CameraAccessController extends CameraManager.AvailabilityCallback implements AutoCloseable {
    private static final String TAG = "CameraAccessController";

    private final Object mLock = new Object();

    private final Context mContext;
    private final VirtualDeviceManagerInternal mVirtualDeviceManagerInternal;
    private final CameraAccessBlockedCallback mBlockedCallback;
    private final CameraManager mCameraManager;
    private final PackageManager mPackageManager;

    @GuardedBy("mLock")
    private int mObserverCount = 0;

    /**
     * Mapping from camera ID to open camera app associations. Key is the camera id, value is the
     * information of the app's uid and package name.
     */
    @GuardedBy("mLock")
    private ArrayMap<String, OpenCameraInfo> mAppsToBlockOnVirtualDevice = new ArrayMap<>();

    static class OpenCameraInfo {
        public String packageName;
        public int packageUid;
    }

    interface CameraAccessBlockedCallback {
        /**
         * Called whenever an app was blocked from accessing a camera.
         * @param appUid uid for the app which was blocked
         */
        void onCameraAccessBlocked(int appUid);
    }

    CameraAccessController(Context context,
            VirtualDeviceManagerInternal virtualDeviceManagerInternal,
            CameraAccessBlockedCallback blockedCallback) {
        mContext = context;
        mVirtualDeviceManagerInternal = virtualDeviceManagerInternal;
        mBlockedCallback = blockedCallback;
        mCameraManager = mContext.getSystemService(CameraManager.class);
        mPackageManager = mContext.getPackageManager();
    }

    /**
     * Starts watching for camera access by uids running on a virtual device, if we were not
     * already doing so.
     */
    public void startObservingIfNeeded() {
        synchronized (mLock) {
            if (mObserverCount == 0) {
                mCameraManager.registerAvailabilityCallback(mContext.getMainExecutor(), this);
            }
            mObserverCount++;
        }
    }

    /**
     * Stop watching for camera access.
     */
    public void stopObservingIfNeeded() {
        synchronized (mLock) {
            mObserverCount--;
            if (mObserverCount <= 0) {
                close();
            }
        }
    }

    /**
     * Need to block camera access for applications running on virtual displays.
     * <p>
     * Apps that open the camera on the main display will need to block camera access if moved to a
     * virtual display.
     *
     * @param runningUids uids of the application running on the virtual display
     */
    public void blockCameraAccessIfNeeded(Set<Integer> runningUids) {
        synchronized (mLock) {
            for (int i = 0; i < mAppsToBlockOnVirtualDevice.size(); i++) {
                final String cameraId = mAppsToBlockOnVirtualDevice.keyAt(i);
                final OpenCameraInfo openCameraInfo = mAppsToBlockOnVirtualDevice.get(cameraId);
                int packageUid = openCameraInfo.packageUid;
                if (runningUids.contains(packageUid)) {
                    final String packageName = openCameraInfo.packageName;
                    startBlocking(packageName, cameraId);
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (mObserverCount < 0) {
                Slog.wtf(TAG, "Unexpected negative mObserverCount: " + mObserverCount);
            } else if (mObserverCount > 0) {
                Slog.w(TAG, "Unexpected close with observers remaining: " + mObserverCount);
            }
        }
        mCameraManager.unregisterAvailabilityCallback(this);
    }

    @Override
    public void onCameraOpened(@NonNull String cameraId, @NonNull String packageName) {
    }

    @Override
    public void onCameraClosed(@NonNull String cameraId) {
    }

    /**
     * Turns on blocking for a particular camera and package.
     */
    private void startBlocking(String packageName, String cameraId) {

    }
}
