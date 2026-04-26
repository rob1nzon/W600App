package com.w600.glasses.protocol

object Cmd {
    // Session
    const val CHALLENGE       = "0001"
    const val CHALLENGE_RESP  = "0002"
    const val MTU_NEGOTIATE   = "0031"
    const val BIND            = "102E"

    // Device info
    const val DEVICE_INFO     = "1001"
    const val BATTERY         = "1003"
    const val DATETIME_SYNC   = "1007"
    const val STORAGE         = "1032"
    const val REBOOT          = "1030"
    const val POWER_OFF       = "1033"
    const val CAMERA_SHUTTER  = "1028"
    const val CAMERA_MODE     = "1029"
    const val CAMERA_RESULT   = "102A"
    const val CAMERA_ZOOM     = "102B"

    // Settings
    const val LANGUAGE        = "2410"

    // Media / Camera
    const val PREVIEW_STATE   = "5711"
    const val AI_AUDIO_STATE  = "5710"
    const val PREVIEW_TOGGLE  = "5720"
    const val SD_CAPACITY     = "5712"
    const val MEDIA_COUNT     = "5713"
    const val MEDIA_LIST      = "5720"
    const val MEDIA_DELETE    = "5730"
    const val MEDIA_DOWNLOAD  = "5731"
    const val MEDIA_PROGRESS  = "5740"
    const val MEDIA_EVENT     = "5750"
    const val MEDIA_LIB_A     = "57A0"
    const val MEDIA_LIB_B     = "57B0"
    const val MEDIA_LIB_B1    = "57B1"
    const val MEDIA_LIB_C     = "5770"
    const val MUSIC_CTRL      = "5410"

    // Unknown handshake
    const val HANDSHAKE_7100  = "7100"
    const val HANDSHAKE_7110  = "7110"
    const val HANDSHAKE_C10A  = "C10A"
}

object Head {
    const val SESSION         = 0x0A.toByte()
    const val DEVICE_INFO     = 0x0B.toByte()
    const val FILE_TRANSFER   = 0x0E.toByte()
    const val CAMERA_PREVIEW  = 0x1A.toByte()
    const val VIDEO_PREVIEW   = 0x1E.toByte()
    const val AI_PREVIEW      = 0x1F.toByte()
    const val NODE_PROTOCOL   = 0x30.toByte()
    const val PHOTO_LIB       = 0x4A.toByte()
}

object DataFmt {
    const val BIN       = 0
    const val PLAIN_TXT = 1
    const val JSON      = 2
    const val NODATA    = 3
    const val ERRCODE   = 4
}

object ActionType {
    const val READ         = 1.toByte()
    const val WRITE        = 2.toByte()
    const val EXECUTE      = 3.toByte()
    const val NOTIFY       = 4.toByte()
    const val RESPONSE_EACH = 100.toByte()
    const val RESPONSE_OK  = 101.toByte()
    const val RESPONSE_FAIL= 102.toByte()
}

object DivideType {
    const val SINGLE      = 0
    const val FIRST       = 1
    const val MIDDLE      = 2
    const val LAST        = 3
}
