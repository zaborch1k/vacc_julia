package com.ihateandroid.blindward

class DetectionResult(

    val x1: Float,

    val y1: Float,

    val x2: Float,

    val y2: Float,

    val confidence: Float,

    val classId: Int

    )
    {

        fun getWidth(): Float = x2 - x1


        fun getHeight(): Float = y2 - y1


        fun getCenterX(): Float = (x1 + x2) / 2f


        fun getCenterY(): Float = (y1 + y2) / 2f
    }