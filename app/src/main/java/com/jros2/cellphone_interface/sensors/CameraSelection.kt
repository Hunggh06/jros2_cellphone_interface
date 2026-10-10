package com.jros2.cellphone_interface.sensors

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

data class RearCameraMetadata(
    val id: String,
    val focalLengths: List<Float>,
    val zoomRatioRange: ClosedFloatingPointRange<Float>? = null
)

data class RearCameraSelection(
    val normalId: String?,
    val ultrawideId: String?,
    val ultrawideZoomRatio: Float? = null
)

object CameraSelection {
    private const val ULTRAWIDE_ZOOM_RATIO = 0.6f

    fun discoverRearCameras(manager: CameraManager): RearCameraSelection {
        val cameras = manager.cameraIdList.mapNotNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }

            RearCameraMetadata(
                id = id,
                focalLengths = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()
                    .orEmpty(),
                zoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    it.lower..it.upper
                }
            )
        }

        return selectRearCameras(cameras)
    }

    fun selectRearCameras(cameras: List<RearCameraMetadata>): RearCameraSelection {
        val normalCamera = cameras.firstOrNull()
        val normalId = normalCamera?.id
        val separateUltrawide = cameras.asSequence()
            .filter { it.id != normalId }
            .mapNotNull { camera ->
                camera.focalLengths
                    .filter { focalLength -> focalLength.isFinite() && focalLength > 0f }
                    .minOrNull()
                    ?.let { focalLength -> camera.id to focalLength }
            }
            .minByOrNull { (_, focalLength) -> focalLength }
        if (separateUltrawide != null) {
            return RearCameraSelection(normalId, separateUltrawide.first)
        }

        val logicalUltrawide = normalCamera?.let { camera ->
            val validFocalLengths = camera.focalLengths.filter { focalLength ->
                focalLength.isFinite() && focalLength > 0f
            }
            if (validFocalLengths.isNotEmpty() && camera.zoomRatioRange?.contains(ULTRAWIDE_ZOOM_RATIO) == true) {
                camera.id
            } else {
                null
            }
        }

        return RearCameraSelection(normalId, logicalUltrawide, logicalUltrawide?.let { ULTRAWIDE_ZOOM_RATIO })
    }
}
