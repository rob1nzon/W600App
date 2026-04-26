package com.w600.glasses.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class DeviceInfo(
    @SerializedName("prod_mode")     val prodMode: String = "",
    @SerializedName("soft_ver")      val softVer: String = "",
    @SerializedName("hard_ver")      val hardVer: String = "",
    @SerializedName("mac_addr")      val macAddr: String = "",
    @SerializedName("dev_id")        val devId: String = "",
    @SerializedName("dev_name")      val devName: String = "",
    @SerializedName("battery_main")  val battery: String = "0",
    @SerializedName("dial_ability")  val dialAbility: String = "",
    @SerializedName("screen")        val screen: String = "",
    @SerializedName("preview_width") val previewWidth: String = "160",
    @SerializedName("preview_height")val previewHeight: String = "120",
    @SerializedName("screen_shape")  val screenShape: String = "0",
    @SerializedName("remain_memory") val remainMemory: Long = 0,
    @SerializedName("total_memory")  val totalMemory: Long = 0,
    @SerializedName("is_charging")   val isCharging: Int = 0,
    @SerializedName("ch")            val canvasH: String = "304",
    @SerializedName("cw")            val canvasW: String = "320"
) {
    val batteryLevel get() = battery.toIntOrNull() ?: 0
    val previewW get() = previewWidth.toIntOrNull() ?: 160
    val previewH get() = previewHeight.toIntOrNull() ?: 120
}

data class MediaCount(
    @SerializedName("photo_num")  val photoNum: String = "0",
    @SerializedName("video_num")  val videoNum: String = "0",
    @SerializedName("record_num") val recordNum: String = "0",
    @SerializedName("music_num")  val musicNum: String = "0"
) {
    val photos  get() = photoNum.toIntOrNull() ?: 0
    val videos  get() = videoNum.toIntOrNull() ?: 0
    val records get() = recordNum.toIntOrNull() ?: 0
}

data class MediaFile(
    @SerializedName("file_id")   val fileId: String = "",
    @SerializedName("file_name") val fileName: String = "",
    @SerializedName("file_size") val fileSize: Long = 0,
    @SerializedName("file_type") val fileType: String = "",  // "photo" / "video"
    @SerializedName("create_time") val createTime: String = "",
    @SerializedName("thumb_url") val thumbUrl: String = ""
)

data class MediaList(
    @SerializedName("total")  val total: Int = 0,
    @SerializedName("page")   val page: Int = 0,
    @SerializedName("files")  val files: List<MediaFile> = emptyList()
)

data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Scanning(val progress: String = "") : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

val gson = Gson()
