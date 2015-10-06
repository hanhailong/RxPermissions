/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tbruyelle.rxpermissions;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rx.Observable;
import rx.functions.Action0;
import rx.functions.FuncN;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

public class RxPermissions {

    private static RxPermissions sSingleton;

    public static RxPermissions getInstance(Context ctx) {
        if (sSingleton == null) {
            sSingleton = new RxPermissions();
            sSingleton.mCtx = ctx.getApplicationContext();
        }
        return sSingleton;
    }

    private Context mCtx;

    // Contains all the current permission requests.
    // Once granted or denied, they are removed from it.
    private Map<String, PublishSubject<Boolean>> mSubjects = new HashMap<>();

    private RxPermissions() {

    }

    /**
     * Register one or several permission requests and returns an observable.
     * <p>
     * For SDK &lt; 23, the observable will immediatly emit true, otherwise
     * the user response to that request.
     * <p>
     * It handles multiple requests to the same permission, in that case the
     * same observable will be returned.
     */
    public Observable<Boolean> request(final String... permissions) {
        if (permissions == null || permissions.length == 0) {
            throw new IllegalArgumentException("RxPermission.request requires at least one input permission");
        }
        if (isGranted(permissions)) {
            // Already granted, or not Android M
            return Observable.just(true);
        }
        return request_(permissions);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private Observable<Boolean> request_(final String... permissions) {

        List<Observable<Boolean>> list = new ArrayList<>(permissions.length);
        List<String> unrequestedPermissions = new ArrayList<>();

        // In case of multiple permissions, we create a observable for each of them.
        // This helps to handle concurrent requests, for instance when there is one
        // request for CAMERA and STORAGE, and another request for CAMERA only, only
        // one observable will be create for the CAMERA.
        // At the end, the observables are combined to have a unique response.
        for (String permission : permissions) {
            PublishSubject<Boolean> subject = mSubjects.get(permission);
            // Create a new subject if not exists OR if completed.
            // This last case occurs on configuration change, and in that case
            // we need to recreate a new subject, but without request the permission
            // again.
            if (subject == null || subject.hasCompleted()) {
                if (subject == null) {
                    unrequestedPermissions.add(permission);
                }
                subject = PublishSubject.create();
                mSubjects.put(permission, subject);
            }
            list.add(subject);
        }
        if (!unrequestedPermissions.isEmpty()) {
            startShadowActivity(permissions);
        }

        return Observable.combineLatest(list, combineLatestBools.INSTANCE).doOnSubscribe(new Action0() {
            @Override
            public void call() {

            }
        });
    }

    void startShadowActivity(String[] permissions) {
        Intent intent = new Intent(mCtx, ShadowActivity.class);
        intent.putExtra("permissions", permissions);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mCtx.startActivity(intent);
    }

    /**
     * Returns true if the permissions is already granted.
     * <p>
     * Always true if SDK &lt; 23.
     */
    public boolean isGranted(String... permissions) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasPermission_(permissions);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean hasPermission_(String... permissions) {
        for (String permission : permissions) {
            if (mCtx.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    void onDestroy() {
        // Invoke onCompleted on all registered subjects.
        // This should unsubscribe the observers.
        for (Subject subject : mSubjects.values()) {
            subject.onCompleted();
        }
    }

    void onRequestPermissionsResult(int requestCode,
                                    String permissions[], int[] grantResults) {
        for (int i = 0; i < permissions.length; i++) {
            // Find the corresponding subject
            PublishSubject<Boolean> subject = mSubjects.get(permissions[i]);
            if (subject == null) {
                // No subject found
                throw new IllegalStateException("RxPermission.onRequestPermissionsResult invoked but didn't find the corresponding permission request.");
            }
            mSubjects.remove(permissions[i]);
            subject.onNext(grantResults[i] == PackageManager.PERMISSION_GRANTED);
            subject.onCompleted();
        }
    }

    private enum combineLatestBools implements FuncN<Boolean> {
        INSTANCE;

        public Boolean call(Object... args) {
            for (Object arg : args) {
                if (!(Boolean) arg) {
                    return false;
                }
            }
            return true;
        }
    }
}
