package com.jros2.cellphone_interface.sensors

import std_msgs.String as RosString
import us.ihmc.fastddsjava.cdr.CDRBuffer

/**
 * std_msgs/String with UTF-8-correct wire size.
 *
 * jros2-android 1.1.7 writes strings as UTF-8 (CDRBuffer.writeString) but its
 * calculateSizeBytes counts chars, so any non-ASCII text is truncated
 * mid-character on publish and arrives garbled on ROS. This subclass keeps the
 * registered DDS type unchanged (still std_msgs/String) and only fixes the
 * size both publisher paths call on the message instance. Safe to delete once
 * upstream fixes the size calculation.
 */
class Utf8String : RosString {
    constructor() : super()

    constructor(text: String) : super() {
        setData(text)
    }

    override fun calculateSizeBytes(currentAlignment: Int): Int {
        // Same formula as the parent, but with UTF-8 byte length instead of
        // char count (identical result for ASCII, correct size otherwise).
        val byteLength = dataAsString.toByteArray(Charsets.UTF_8).size
        var size = currentAlignment
        size += 4 + CDRBuffer.alignment(size, 4) + byteLength + 1
        return size - currentAlignment
    }
}
