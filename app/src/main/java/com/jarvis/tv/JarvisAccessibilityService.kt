package com.jarvis.tv

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class JarvisAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CLICK_TEXT -> clickByText(intent.getStringExtra("text").orEmpty())
                ACTION_SET_TEXT -> setText(intent.getStringExtra("text").orEmpty(), intent.getStringExtra("target").orEmpty())
                ACTION_SCROLL_FORWARD -> scroll(true)
                ACTION_SCROLL_BACKWARD -> scroll(false)
                ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                ACTION_REFRESH_UI -> persistSnapshot()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val f=IntentFilter().apply {
            addAction(ACTION_CLICK_TEXT); addAction(ACTION_SET_TEXT); addAction(ACTION_SCROLL_FORWARD); addAction(ACTION_SCROLL_BACKWARD); addAction(ACTION_BACK); addAction(ACTION_HOME); addAction(ACTION_REFRESH_UI)
        }
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver,f)
        getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putBoolean("accessibility_connected",true).apply()
        persistSnapshot(); showBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = TextView(this).apply {
            text = "AI"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xD91B1F2A.toInt()); setPadding(30, 22, 30, 22)
            isClickable = true; contentDescription = "Hablar con Jarvis"
            setOnClickListener { startActivity(Intent(this@JarvisAccessibilityService, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra("start_voice", true) }) }
        }
        bubble = view
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.END; x=42; y=42 }
        try { wm.addView(view, params) } catch (_: Exception) { bubble = null }
    }

    private fun nodes():List<AccessibilityNodeInfo>{
        val out=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo?){ if(n==null)return; out+=n; for(i in 0 until n.childCount) walk(n.getChild(i)) }
        walk(rootInActiveWindow); return out
    }
    private fun label(n:AccessibilityNodeInfo):String = listOf(n.text?.toString().orEmpty(),n.contentDescription?.toString().orEmpty(),n.hintText?.toString().orEmpty(),n.viewIdResourceName.orEmpty().substringAfterLast('/').replace('_',' ')).firstOrNull{it.isNotBlank()}.orEmpty().trim()
    private fun persistSnapshot(){
        val a=JSONArray(); nodes().take(180).forEach{n-> val t=label(n); if(t.isBlank()&&!n.isClickable&&!n.isEditable)return@forEach; a.put(JSONObject().put("text",t.take(240)).put("hint",n.hintText?.toString().orEmpty()).put("viewId",n.viewIdResourceName.orEmpty()).put("clickable",n.isClickable).put("editable",n.isEditable).put("scrollable",n.isScrollable).put("enabled",n.isEnabled)) }
        getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putString("accessibility_ui",a.toString()).putLong("accessibility_ui_at",System.currentTimeMillis()).apply()
    }
    private fun clickableParent(n0:AccessibilityNodeInfo?):AccessibilityNodeInfo?{ var n=n0; var d=0; while(n!=null&&d++<6){if(n.isClickable&&n.isEnabled)return n;n=n.parent};return null }
    private fun clickByText(q0:String){ val q=q0.trim(); if(q.isBlank())return; val c=nodes().filter{label(it).contains(q,true)}; (c.firstOrNull{it.isClickable}?:c.firstOrNull()?.let{clickableParent(it)})?.performAction(AccessibilityNodeInfo.ACTION_CLICK); persistSnapshot() }
    private fun setText(v:String,target:String){ val c=nodes().filter{it.isEditable&&it.isEnabled&&!it.isPassword}; val n=if(target.isBlank())c.firstOrNull() else c.firstOrNull{label(it).contains(target,true)}?:c.firstOrNull(); n?.let{ val b=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,v)}; it.performAction(AccessibilityNodeInfo.ACTION_FOCUS); it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)}; persistSnapshot() }
    private fun scroll(f:Boolean){ nodes().firstOrNull{it.isScrollable&&it.isEnabled}?.performAction(if(f)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); persistSnapshot() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putString("foreground_package",event?.packageName?.toString().orEmpty()).apply(); persistSnapshot() }
    override fun onInterrupt() {}
    override fun onDestroy() { getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putBoolean("accessibility_connected",false).apply(); runCatching{unregisterReceiver(receiver)}; bubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }; bubble=null; super.onDestroy() }

    companion object{
        const val ACTION_CLICK_TEXT="com.jarvis.tv.action.CLICK_TEXT"; const val ACTION_SET_TEXT="com.jarvis.tv.action.SET_TEXT"; const val ACTION_SCROLL_FORWARD="com.jarvis.tv.action.SCROLL_FORWARD"; const val ACTION_SCROLL_BACKWARD="com.jarvis.tv.action.SCROLL_BACKWARD"; const val ACTION_BACK="com.jarvis.tv.action.BACK"; const val ACTION_HOME="com.jarvis.tv.action.HOME"; const val ACTION_REFRESH_UI="com.jarvis.tv.action.REFRESH_UI"
    }
}
