package com.example.raybanvision.mock

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

/**
 * debug 빌드 전용 — 물리 글라스 없이 폰을 목 디바이스로 사용하기 위한 Mock Device Kit 배선.
 *
 * 흐름: [enable] 로 MDK 활성화(등록·권한 시뮬레이션) → [pairMockGlasses] 로 목 글라스 페어링 후
 * 전원/착용 상태로 만들고, 폰 후면 카메라를 라이브 스트리밍 피드로 연결한다.
 * 이후 실제 세션 흐름(startSession → addStream → capturePhoto)이 목 디바이스에 그대로 동작한다.
 *
 * 주의: MDK 0.8.0 목 디바이스는 카메라·터치만 지원하고 디스플레이는 지원하지 않는다.
 * 따라서 목으로는 사진 촬영까지만 테스트되고, 글라스 디스플레이 출력은 실제 글라스에서 확인해야 한다.
 */
object MockGlassesInitializer {
    private const val TAG = "MockGlassesInit"

    /** MDK 활성화. 반드시 Wearables.initialize 이전에 호출한다. */
    fun enable(context: Context) {
        val mdk = MockDeviceKit.getInstance(context)
        if (!mdk.isEnabled) {
            mdk.enable(
                MockDeviceKitConfig(
                    initiallyRegistered = true,
                    initialPermissionsGranted = true,
                ),
            )
            Log.i(TAG, "MockDeviceKit enabled")
        }
    }

    /** 목 글라스 페어링 + 전원/펼침/착용 + 후면 카메라를 라이브 피드로 연결. */
    fun pairMockGlasses(context: Context) {
        val mdk = MockDeviceKit.getInstance(context)
        if (mdk.pairedDevices.isNotEmpty()) return

        mdk.pairGlasses(GlassesModel.RAYBAN_META)
            .onSuccess { device ->
                device.powerOn()
                (device as? MockGlasses)?.let { glasses ->
                    glasses.unfold()
                    glasses.don()
                    glasses.services.camera.setCameraFeed(CameraFacing.BACK)
                }
                Log.i(TAG, "Mock glasses paired: ${device.deviceIdentifier}")
            }
            .onFailure { error, _ ->
                Log.e(TAG, "pairGlasses failed: ${error.description}")
            }
    }
}
