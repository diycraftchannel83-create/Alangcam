package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import com.example.data.model.GpsTelemetry
import com.example.data.model.SpeedUnit
import com.example.ui.theme.HudWhite
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.NeonYellow
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.RajdhaniFontFamily
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpeedometerCockpit(
    telemetry: GpsTelemetry,
    speedUnit: SpeedUnit,
    showGearRpm: Boolean = true,
    isLandscape: Boolean = true,
    modifier: Modifier = Modifier
) {
    val speed = (telemetry.speedKmh * speedUnit.multiplierFromKmh).coerceIn(0f, 220f)
    val animatedSpeed by animateFloatAsState(speed, tween(200), label = "gps_speed_needle")
    val gaugeSize = if (isLandscape) 184.dp else 214.dp

    Column(modifier.testTag("speedometer_gauge"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(gaugeSize)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension * 0.44f
                val track = size.minDimension * 0.024f
                drawArc(Color(0x401E2B40), 135f, 270f, false, Offset(cx-r,cy-r), Size(r*2,r*2),
                    style = Stroke(track, cap = StrokeCap.Round))
                drawArc(NeonRed.copy(alpha=.5f), 135f+270f*.82f, 48.6f, false, Offset(cx-r,cy-r), Size(r*2,r*2),
                    style = Stroke(track*1.3f, cap = StrokeCap.Round))
                val fraction = (animatedSpeed/200f).coerceIn(0f,1f)
                if (fraction > .01f) {
                    val color = when { animatedSpeed>160f -> NeonRed; animatedSpeed>100f -> NeonAmber; else -> NeonCyan }
                    drawArc(color,135f,270f*fraction,false,Offset(cx-r,cy-r),Size(r*2,r*2),
                        style=Stroke(track*1.5f,cap=StrokeCap.Round))
                }
                for (i in 0..40) {
                    val angle=Math.toRadians(135.0+270.0*i/40.0)
                    val major=i%4==0
                    val mid=i%2==0
                    val tickSpeed=i*5
                    val inner=r-(if(major)18f else if(mid)11f else 6f)
                    val outer=r-2f
                    val color=when { tickSpeed>=160 -> NeonRed; major -> NeonCyan; else -> NeonCyan.copy(alpha=.45f) }
                    drawLine(color,Offset(cx+cos(angle).toFloat()*inner,cy+sin(angle).toFloat()*inner),
                        Offset(cx+cos(angle).toFloat()*outer,cy+sin(angle).toFloat()*outer),
                        if(major)3.2f else if(mid)1.8f else 1f,cap=StrokeCap.Round)
                    if (major) {
                        val nr=r-32f
                        val c=when { tickSpeed>=160 -> android.graphics.Color.rgb(255,42,85); tickSpeed>=100 -> android.graphics.Color.rgb(255,214,0); else -> android.graphics.Color.rgb(0,240,255) }
                        val p=AndroidPaint().apply {
                            isAntiAlias = true
                            setColor(c)
                            setTextSize(size.minDimension*.052f)
                            textAlign = AndroidPaint.Align.CENTER
                            typeface = Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
                            setShadowLayer(4f,0f,0f,c)
                        }
                        drawContext.canvas.nativeCanvas.drawText(tickSpeed.toString(),
                            cx+cos(angle).toFloat()*nr,cy+sin(angle).toFloat()*nr+p.textSize*.35f,p)
                    }
                }
                // Short needle rides the perimeter; no center spindle.
                val a=Math.toRadians(135.0+270.0*animatedSpeed/200.0)
                val baseR=r*.94f
                val tipR=r*.68f
                val base=Offset(cx+cos(a).toFloat()*baseR,cy+sin(a).toFloat()*baseR)
                val tip=Offset(cx+cos(a).toFloat()*tipR,cy+sin(a).toFloat()*tipR)
                drawLine(NeonYellow.copy(alpha=.38f),base,tip,13f,cap=StrokeCap.Round)
                drawLine(NeonYellow,base,tip,5.5f,cap=StrokeCap.Round)
                drawLine(Color(0xFFFFFDE7),base,tip,1.8f,cap=StrokeCap.Round)
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Text(animatedSpeed.toInt().toString(),color=HudWhite,
                    fontSize=if(isLandscape)72.sp else 84.sp,fontWeight=FontWeight.Bold,
                    fontFamily=OrbitronFontFamily,letterSpacing=.5.sp)
                Text(speedUnit.label.uppercase(),color=NeonCyan,fontSize=20.sp,
                    fontWeight=FontWeight.Bold,fontFamily=RajdhaniFontFamily,letterSpacing=1.sp)
            }
        }
        if (showGearRpm) {
            val gear=if(telemetry.gear.isNotEmpty()&&telemetry.gear!="--") "GEAR ${telemetry.gear} • ${telemetry.rpm} RPM" else "GEAR / RPM --"
            Text(gear,color=NeonGreen,fontSize=22.sp,fontWeight=FontWeight.Bold,fontFamily=RajdhaniFontFamily)
        }
    }
}
