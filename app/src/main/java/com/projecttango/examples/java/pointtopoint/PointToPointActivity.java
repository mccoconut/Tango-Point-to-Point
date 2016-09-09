/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.projecttango.examples.java.pointtopoint;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import android.app.Activity;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.scene.ASceneFrameCallback;
import org.rajawali3d.surface.RajawaliSurfaceView;

import java.util.ArrayList;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projecttango.rajawali.DeviceExtrinsics;
import com.projecttango.rajawali.ScenePoseCalculator;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;

public class PointToPointActivity extends Activity {
    private static final String TAG = PointToPointActivity.class.getSimpleName();

    private static final int UPDATE_UI_INTERVAL_MS = 100;

    public static final TangoCoordinateFramePair FRAME_PAIR = new TangoCoordinateFramePair(
            TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
            TangoPoseData.COORDINATE_FRAME_DEVICE);
    private static final int INVALID_TEXTURE_ID = 0;

    private RajawaliSurfaceView mSurfaceView;
    private PointToPointRenderer mRenderer;
    private TangoCameraIntrinsics mIntrinsics;
    private TangoPointCloudManager mPointCloudManager;
    private Tango mTango;
    private boolean mIsConnected = false;
    private double mCameraPoseTimestamp = 0;
    private TextView mDistanceMeasure;

    private int mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
    private AtomicBoolean mIsFrameAvailableTangoThread = new AtomicBoolean(false);
    private double mRgbTimestampGlThread;

    private float[][] mLinePoints = new float[2][3];
    private boolean mPointSwitch = true;
    private boolean veryFirstPoint=true;

    private Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSurfaceView = (RajawaliSurfaceView) findViewById(R.id.ar_view);
        mRenderer = new PointToPointRenderer(this);
        mSurfaceView.setSurfaceRenderer(mRenderer);
        mPointCloudManager = new TangoPointCloudManager();
        mDistanceMeasure = (TextView) findViewById(R.id.distance_textview);
        mLinePoints[0] = null;
        mLinePoints[1] = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        clearLine();

        synchronized (this) {
            if (mIsConnected) {
                mRenderer.getCurrentScene().clearFrameCallbacks();
                mTango.disconnectCamera(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
                mTango.disconnect();
                mIsConnected = false;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        synchronized (this) {
            if (!mIsConnected) {
                mTango = new Tango(PointToPointActivity.this, new Runnable() {
                    // Pass in a Runnable to be called from UI thread when Tango is ready,
                    // this Runnable will be running on a new thread.
                    // When Tango is ready, we can call Tango functions safely here only
                    // when there is no UI thread changes involved.
                    @Override
                    public void run() {
                        try {
                            TangoSupport.initialize();
                            connectTango();
                            connectRenderer();
                            mIsConnected = true;
                        } catch (TangoOutOfDateException e) {
                            Log.e(TAG, getString(R.string.tango_out_of_date_exception), e);
                        }
                    }
                });
            }
        }
        mHandler.post(mUpdateUiLoopRunnable);
    }

    private void connectTango() {
        TangoConfig config = mTango.getConfig(
                TangoConfig.CONFIG_TYPE_DEFAULT);
        config.putBoolean(
                TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        mTango.connect(config);

        ArrayList<TangoCoordinateFramePair> framePairs =
                new ArrayList<TangoCoordinateFramePair>();
        mTango.connectListener(framePairs, new OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(TangoPoseData pose) {
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                    mIsFrameAvailableTangoThread.set(true);
                    mSurfaceView.requestRender();
                }
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                mPointCloudManager.updateXyzIj(xyzIj);
            }

            @Override
            public void onTangoEvent(TangoEvent event) {
            }
        });

        mIntrinsics = mTango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
    }

    private void connectRenderer() {
        mRenderer.getCurrentScene().registerFrameCallback(new ASceneFrameCallback() {
            @Override
            public void onPreFrame(long sceneTime, double deltaTime) {

                synchronized (PointToPointActivity.this) {
                    if (!mIsConnected) {
                        return;
                    }

                    if (!mRenderer.isSceneCameraConfigured()) {
                        mRenderer.setProjectionMatrix(mIntrinsics);
                    }

                    if (mConnectedTextureIdGlThread != mRenderer.getTextureId()) {
                        mTango.connectTextureId(TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                                mRenderer.getTextureId());
                        mConnectedTextureIdGlThread = mRenderer.getTextureId();
                        Log.d(TAG, "connected to texture id: " + mRenderer.getTextureId());
                    }

                    if (mIsFrameAvailableTangoThread.compareAndSet(true, false)) {
                        mRgbTimestampGlThread =
                                mTango.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
                    }

                    if (mRgbTimestampGlThread > mCameraPoseTimestamp) {
                        TangoPoseData lastFramePose = TangoSupport.getPoseAtTime(
                                mRgbTimestampGlThread,
                                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                                TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                                TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL, 0);
                        if (lastFramePose.statusCode == TangoPoseData.POSE_VALID) {
                            mRenderer.updateRenderCameraPose(lastFramePose);
                            mCameraPoseTimestamp = lastFramePose.timestamp;
                        } else {
                            Log.w(TAG, "Can't get device pose at time: " +
                                    mRgbTimestampGlThread);
                        }
                    }
                }
            }

            @Override
            public void onPreDraw(long sceneTime, double deltaTime) {

            }

            @Override
            public void onPostFrame(long sceneTime, double deltaTime) {

            }

            @Override
            public boolean callPreFrame() {
                return true;
            }
        });
    }

    public void addPoint(View view) {
        float u = .5f;
        float v = .5f;

        try {
            float[] rgbPoint;
            synchronized (this) {
                rgbPoint = getDepthAtTouchPosition(u, v, mRgbTimestampGlThread);
            }
            if (rgbPoint != null) {
                updateLine(rgbPoint);
                mRenderer.setLine(generateEndpoints());
            } else {
                Log.w(TAG, "Point was null.");
            }


        } catch (TangoException t) {
            Toast.makeText(getApplicationContext(),
                    R.string.failed_measurement,
                    Toast.LENGTH_SHORT).show();
            Log.e(TAG, getString(R.string.failed_measurement), t);
        } catch (SecurityException t) {
            Toast.makeText(getApplicationContext(),
                    R.string.failed_permissions,
                    Toast.LENGTH_SHORT).show();
            Log.e(TAG, getString(R.string.failed_permissions), t);
        }
    }


    private float[] getDepthAtTouchPosition(float u, float v, double rgbTimestamp) {
        TangoXyzIjData xyzIj = mPointCloudManager.getLatestXyzIj();
        if (xyzIj == null) {
            return null;
        }

        TangoPoseData colorTdepthPose = TangoSupport.calculateRelativePose(
                rgbTimestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                xyzIj.timestamp, TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH);

        float[] point = TangoSupport.getDepthAtPointNearestNeighbor(xyzIj, mIntrinsics,
                colorTdepthPose, u, v);
        if (point == null) {
            return null;
        }

        TangoSupport.TangoMatrixTransformData transform =
                TangoSupport.getMatrixTransformAtTime(xyzIj.timestamp,
                        TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                        TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
                        TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                        TangoSupport.TANGO_SUPPORT_ENGINE_TANGO);
        if (transform.statusCode == TangoPoseData.POSE_VALID) {
            float[] dephtPoint = new float[]{point[0], point[1], point[2], 1};
            float[] openGlPoint = new float[4];
            Matrix.multiplyMV(openGlPoint, 0, transform.matrix, 0, dephtPoint, 0);
            return openGlPoint;
        } else {
            Log.w(TAG, "Could not get depth camera transform at time " + xyzIj.timestamp);
        }
        return null;
    }

    private synchronized void updateLine(float[] worldPoint) {
        if (!veryFirstPoint) {
            if (mPointSwitch) {
                mPointSwitch = !mPointSwitch;
                mLinePoints[0] = worldPoint;
                return;
            }
            mPointSwitch = !mPointSwitch;
            mLinePoints[1] = worldPoint;
        } else {
            mLinePoints[0]=worldPoint;
            mLinePoints[1]=getDepthAtTouchPosition(.505f, .5f, mRgbTimestampGlThread);;
            veryFirstPoint=false;
        }
    }

    private synchronized Stack<Vector3> generateEndpoints() {

        if (mLinePoints[0] != null && mLinePoints[1] != null) {
            Stack<Vector3> points = new Stack<Vector3>();
            points.push(new Vector3(mLinePoints[0][0], mLinePoints[0][1], mLinePoints[0][2]));
            points.push(new Vector3(mLinePoints[1][0], mLinePoints[1][1], mLinePoints[1][2]));
            return points;
        }
        return null;
    }

    public void clear(View view) {clearLine();}

    private synchronized void clearLine() {
        mLinePoints[0] = null;
        mLinePoints[1] = null;
        mPointSwitch = true;
        veryFirstPoint=true;
        mRenderer.setLine(null);
    }

    private synchronized String getPointSeparation() {
        if (mLinePoints[0] == null || mLinePoints[1] == null) {
            return "Null";
        }
        float[] p1 = mLinePoints[0];
        float[] p2 = mLinePoints[1];
        double separation = Math.sqrt(
                Math.pow(p1[0] - p2[0], 2) +
                        Math.pow(p1[1] - p2[1], 2) +
                        Math.pow(p1[2] - p2[2], 2));
        return String.format("%.2f", separation) + " "+getString(R.string.meters);
    }

    private Runnable mUpdateUiLoopRunnable = new Runnable() {
        public void run() {
            updateUi();
            mHandler.postDelayed(this, UPDATE_UI_INTERVAL_MS);
        }
    };

    private synchronized void updateUi() {
        try {
            mDistanceMeasure.setText(getPointSeparation());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
