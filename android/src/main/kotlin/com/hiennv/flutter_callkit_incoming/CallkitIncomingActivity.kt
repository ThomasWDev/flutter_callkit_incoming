package com.hiennv.flutter_callkit_incoming

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hiennv.flutter_callkit_incoming.widgets.RippleRelativeLayout
import de.hdodenhof.circleimageview.CircleImageView
import kotlin.math.abs
import android.view.ViewGroup.MarginLayoutParams
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log

class CallkitIncomingActivity : Activity() {

    companion object {
        private const val TAG = "CallkitIncomingActivity"

        private const val ACTION_ENDED_CALL_INCOMING =
            "com.hiennv.flutter_callkit_incoming.ACTION_ENDED_CALL_INCOMING"

        fun getIntent(context: Context, data: Bundle) =
            Intent(CallkitConstants.ACTION_CALL_INCOMING).apply {
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_INCOMING}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

        fun getIntentEnded(context: Context, isAccepted: Boolean): Intent {
            val intent = Intent("${context.packageName}.${ACTION_ENDED_CALL_INCOMING}")
            intent.putExtra("ACCEPTED", isAccepted)
            return intent
        }
    }

    /** Extract the callId from the incoming data bundle for logging context. */
    private fun getCallId(): String {
        val data = intent?.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
        return data?.getString(CallkitConstants.EXTRA_CALLKIT_ID, "unknown") ?: "unknown"
    }

    inner class EndedCallkitIncomingBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isFinishing) {
                val isAccepted = intent.getBooleanExtra("ACCEPTED", false)
                SentryHelper.log(TAG,
                    "[CALLKIT_ACTIVITY] EndedBroadcast received: isAccepted=$isAccepted, callId=${getCallId()}",
                    data = mapOf("callId" to getCallId(), "isAccepted" to isAccepted))
                if (isAccepted) {
                    finishDelayed()
                } else {
                    finishTask()
                }
            }
        }
    }

    private var endedCallkitIncomingBroadcastReceiver = EndedCallkitIncomingBroadcastReceiver()

    private lateinit var ivBackground: ImageView
    private lateinit var llBackgroundAnimation: RippleRelativeLayout

    private lateinit var tvNameCaller: TextView
    private lateinit var tvNumber: TextView
    private lateinit var tvAppName: TextView
    private lateinit var ivLogo: ImageView
    private lateinit var ivAvatar: CircleImageView

    private lateinit var llAction: LinearLayout
    private lateinit var ivAcceptCall: ImageView
    private lateinit var tvAccept: TextView

    private lateinit var ivDeclineCall: ImageView
    private lateinit var tvDecline: TextView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] onCreate - Activity CREATED, taskId=$taskId",
            data = mapOf("taskId" to taskId))

        // GMA-630: On Android 14+ with CallStyle notifications, Samsung OneUI shows BOTH
        // a heads-up notification AND launches this full-screen Activity simultaneously
        // (dual UI bug). Fix: when the device is NOT locked, finish this Activity
        // immediately and let the system CallStyle notification handle the UI.
        // Only show this Activity when the screen is locked (where it's needed to wake screen).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isDeviceLocked = keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked
            val isScreenOn = powerManager.isInteractive

            if (isScreenOn && !isDeviceLocked) {
                SentryHelper.log(TAG,
                    "[CALLKIT_ACTIVITY] FINISHING - Device unlocked, CallStyle notification handles UI",
                    data = mapOf("callId" to getCallId(), "isScreenOn" to isScreenOn,
                        "isDeviceLocked" to isDeviceLocked))
                finish()
                return
            }
            SentryHelper.log(TAG,
                "[CALLKIT_ACTIVITY] SHOWING - Device locked/screen off, showing full-screen UI",
                data = mapOf("callId" to getCallId(), "isScreenOn" to isScreenOn,
                    "isDeviceLocked" to isDeviceLocked))
        }

        requestedOrientation = if (!Utils.isTablet(this@CallkitIncomingActivity)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        transparentStatusAndNavigation()
        setContentView(R.layout.activity_callkit_incoming)
        initView()
        incomingData(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                endedCallkitIncomingBroadcastReceiver,
                IntentFilter("${packageName}.${ACTION_ENDED_CALL_INCOMING}"),
                Context.RECEIVER_EXPORTED,
            )
        } else {
            registerReceiver(
                endedCallkitIncomingBroadcastReceiver,
                IntentFilter("${packageName}.${ACTION_ENDED_CALL_INCOMING}")
            )
        }
    }

    private fun wakeLockRequest(duration: Long) {

        val pm = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Callkit:PowerManager"
        )
        wakeLock.acquire(duration)
    }

    private fun transparentStatusAndNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            setWindowFlag(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, true
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setWindowFlag(
                (WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION), false
            )
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    private fun setWindowFlag(bits: Int, on: Boolean) {
        val win: Window = window
        val winParams: WindowManager.LayoutParams = win.attributes
        if (on) {
            winParams.flags = winParams.flags or bits
        } else {
            winParams.flags = winParams.flags and bits.inv()
        }
        win.attributes = winParams
    }


    private fun incomingData(intent: Intent) {
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
        if (data == null) {
            SentryHelper.log(TAG,
                "[CALLKIT_ACTIVITY] incomingData - data is NULL, finishing",
                SentryHelper.Level.ERROR)
            finish()
        }

        val callId = data?.getString(CallkitConstants.EXTRA_CALLKIT_ID, "unknown") ?: "unknown"
        val callerName = data?.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "") ?: ""
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] incomingData - callId=$callId, caller=$callerName",
            data = mapOf("callId" to callId, "callerName" to callerName))

        val isShowFullLockedScreen =
            data?.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_FULL_LOCKED_SCREEN, true)
        if (isShowFullLockedScreen == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }

        val textColor = data?.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_COLOR, "#ffffff")
        val isShowCallID = data?.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_CALL_ID, false)
        tvNameCaller.text = data?.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "")
        tvNumber.text = data?.getString(CallkitConstants.EXTRA_CALLKIT_HANDLE, "")
        tvNumber.visibility = if (isShowCallID == true) View.VISIBLE else View.INVISIBLE
        
        // Show app name if provided
        val appName = data?.getString(CallkitConstants.EXTRA_CALLKIT_APP_NAME, "")
        if (!appName.isNullOrEmpty()) {
            tvAppName.text = appName
            tvAppName.visibility = View.VISIBLE
        }

        try {
            tvNameCaller.setTextColor(Color.parseColor(textColor))
            tvNumber.setTextColor(Color.parseColor(textColor))
        } catch (error: Exception) {
        }

        val isShowLogo = data?.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_LOGO, false)
        ivLogo.visibility = if (isShowLogo == true) View.VISIBLE else View.INVISIBLE
        var logoUrl = data?.getString(CallkitConstants.EXTRA_CALLKIT_LOGO_URL, "")
        if (!logoUrl.isNullOrEmpty()) {
            if (!logoUrl.startsWith("http://", true) && !logoUrl.startsWith("https://", true)) {
                logoUrl = String.format("file:///android_asset/flutter_assets/%s", logoUrl)
            }
            val headers =
                data?.getSerializable(CallkitConstants.EXTRA_CALLKIT_HEADERS) as HashMap<String, Any?>
            ImageLoaderProvider.loadImage(this@CallkitIncomingActivity, logoUrl, headers, R.drawable.transparent, ivLogo)
        }

        var avatarUrl = data?.getString(CallkitConstants.EXTRA_CALLKIT_AVATAR, "")
        if (!avatarUrl.isNullOrEmpty()) {
            ivAvatar.visibility = View.VISIBLE
            if (!avatarUrl.startsWith("http://", true) && !avatarUrl.startsWith("https://", true)) {
                avatarUrl = String.format("file:///android_asset/flutter_assets/%s", avatarUrl)
            }
            val headers =
                data?.getSerializable(CallkitConstants.EXTRA_CALLKIT_HEADERS) as HashMap<String, Any?>
            ImageLoaderProvider.loadImage(this@CallkitIncomingActivity, avatarUrl, headers, R.drawable.ic_default_avatar, ivAvatar)
        }

        val callType = data?.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, 0) ?: 0
        if (callType > 0) {
            ivAcceptCall.setImageResource(R.drawable.ic_video)
        }
        val duration = data?.getLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 0L) ?: 0L
        wakeLockRequest(duration)

        finishTimeout(data, duration)

        val textAccept = data?.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_ACCEPT, "")
        tvAccept.text =
            if (TextUtils.isEmpty(textAccept)) getString(R.string.text_accept) else textAccept
        val textDecline = data?.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_DECLINE, "")
        tvDecline.text =
            if (TextUtils.isEmpty(textDecline)) getString(R.string.text_decline) else textDecline
            
        // FIX (Build 92): Hide decline button for emergency calls
        // FIX (Build 144): Hide ENTIRE decline container including ripple animation
        val isShowDeclineButton = data?.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_DECLINE_BUTTON, true) ?: true
        if (!isShowDeclineButton) {
            SentryHelper.log(TAG,
                "[CALLKIT_ACTIVITY] Hiding decline button (emergency call), callId=$callId")
            ivDeclineCall.visibility = View.GONE
            tvDecline.visibility = View.GONE
            // Hide the parent RippleRelativeLayout and its LinearLayout container
            // to prevent the ripple animation from showing without a button
            (ivDeclineCall.parent as? android.view.View)?.visibility = View.GONE
            (ivDeclineCall.parent?.parent as? android.view.View)?.visibility = View.GONE
        }

        try {
            tvAccept.setTextColor(Color.parseColor(textColor))
            tvDecline.setTextColor(Color.parseColor(textColor))
        } catch (error: Exception) {
        }

        val backgroundColor =
            data?.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_COLOR, "#0955fa")
        try {
            ivBackground.setBackgroundColor(Color.parseColor(backgroundColor))
        } catch (error: Exception) {
        }
        var backgroundUrl = data?.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_URL, "")
        if (!backgroundUrl.isNullOrEmpty()) {
            if (!backgroundUrl.startsWith("http://", true) && !backgroundUrl.startsWith(
                    "https://",
                    true
                )
            ) {
                backgroundUrl =
                    String.format("file:///android_asset/flutter_assets/%s", backgroundUrl)
            }
            val headers =
                data?.getSerializable(CallkitConstants.EXTRA_CALLKIT_HEADERS) as HashMap<String, Any?>
            ImageLoaderProvider.loadImage(this@CallkitIncomingActivity, backgroundUrl, headers, R.drawable.transparent, ivBackground)
        }
    }

    private fun finishTimeout(data: Bundle?, duration: Long) {
        val currentSystemTime = System.currentTimeMillis()
        val timeStartCall =
            data?.getLong(CallkitNotificationManager.EXTRA_TIME_START_CALL, currentSystemTime)
                ?: currentSystemTime

        val timeOut = duration - abs(currentSystemTime - timeStartCall)
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] finishTimeout set: ${timeOut}ms, callId=${getCallId()}",
            data = mapOf("timeoutMs" to timeOut, "callId" to getCallId()))
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                SentryHelper.log(TAG,
                    "[CALLKIT_ACTIVITY] Timeout reached - finishing, callId=${getCallId()}",
                    SentryHelper.Level.WARNING,
                    mapOf("callId" to getCallId()))
                finishTask()
            }
        }, timeOut)
    }

    private fun initView() {
        ivBackground = findViewById(R.id.ivBackground)
        llBackgroundAnimation = findViewById(R.id.llBackgroundAnimation)
        llBackgroundAnimation.layoutParams.height =
            Utils.getScreenWidth() + Utils.getStatusBarHeight(this@CallkitIncomingActivity)
        llBackgroundAnimation.startRippleAnimation()

        tvNameCaller = findViewById(R.id.tvNameCaller)
        tvNumber = findViewById(R.id.tvNumber)
        tvAppName = findViewById(R.id.tvAppName)
        ivLogo = findViewById(R.id.ivLogo)
        ivAvatar = findViewById(R.id.ivAvatar)

        llAction = findViewById(R.id.llAction)

        val params = llAction.layoutParams as MarginLayoutParams
        params.setMargins(0, 0, 0, Utils.getNavigationBarHeight(this@CallkitIncomingActivity))
        llAction.layoutParams = params

        ivAcceptCall = findViewById(R.id.ivAcceptCall)
        tvAccept = findViewById(R.id.tvAccept)
        ivDeclineCall = findViewById(R.id.ivDeclineCall)
        tvDecline = findViewById(R.id.tvDecline)
        animateAcceptCall()

        // Set click listeners on BOTH the ImageView AND the parent containers
        // to ensure touch events are captured reliably on lock screen
        ivAcceptCall.setOnClickListener {
            onAcceptClick()
        }
        ivDeclineCall.setOnClickListener {
            onDeclineClick()
        }
        // Also set click on the parent LinearLayout containers for larger touch target
        (ivAcceptCall.parent?.parent as? android.view.View)?.setOnClickListener {
            onAcceptClick()
        }
        (ivDeclineCall.parent?.parent as? android.view.View)?.setOnClickListener {
            onDeclineClick()
        }
        // And on the text labels
        tvAccept.setOnClickListener {
            onAcceptClick()
        }
        tvDecline.setOnClickListener {
            onDeclineClick()
        }

        SentryHelper.log(TAG, "[CALLKIT_ACTIVITY] initView complete - click listeners set")
    }

    private fun animateAcceptCall() {
        val shakeAnimation =
            AnimationUtils.loadAnimation(this@CallkitIncomingActivity, R.anim.shake_anim)
        ivAcceptCall.animation = shakeAnimation
    }


    private fun onAcceptClick() {
        val callId = getCallId()
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] ACCEPT TAPPED - callId=$callId",
            data = mapOf("callId" to callId, "action" to "accept"))
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)


        CallkitNotificationService.startServiceWithAction(
            this@CallkitIncomingActivity,
            CallkitConstants.ACTION_CALL_ACCEPT,
            data
        )


        val acceptIntent =
            TransparentActivity.getIntent(this, CallkitConstants.ACTION_CALL_ACCEPT, data)
        startActivity(acceptIntent)

        // GMA-627: Removed dismissKeyguard() call
        // requestDismissKeyguard() only works for PIN/pattern locks, NOT biometric locks.
        // For biometric locks, it fails silently and the call never connects.
        // Since setShowWhenLocked(true) is already set in onCreate(), the call activity
        // will display over the lock screen without needing to dismiss it.
        // The user can unlock their phone after the call if needed.
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] Accept processed - finishing Activity, callId=$callId",
            data = mapOf("callId" to callId))
        finish()
    }

    // GMA-627: Deprecated - requestDismissKeyguard doesn't work with biometric locks
    // Keeping for reference but no longer called
    @Suppress("unused")
    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    private fun onDeclineClick() {
        val callId = getCallId()
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] DECLINE TAPPED - callId=$callId",
            data = mapOf("callId" to callId, "action" to "decline"))
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)

        val intent =
            CallkitIncomingBroadcastReceiver.getIntentDecline(this@CallkitIncomingActivity, data)
        sendBroadcast(intent)
        finishTask()
    }

    private fun finishDelayed() {
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] finishDelayed (1s) - callId=${getCallId()}")
        Handler(Looper.getMainLooper()).postDelayed({
            finishTask()
        }, 1000)
    }

    private fun finishTask() {
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] finishTask called - callId=${getCallId()}",
            data = mapOf("callId" to getCallId()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] onDestroy - isFinishing=$isFinishing, callId=${getCallId()}",
            if (isFinishing) SentryHelper.Level.INFO else SentryHelper.Level.WARNING,
            mapOf("callId" to getCallId(), "isFinishing" to isFinishing))
        unregisterReceiver(endedCallkitIncomingBroadcastReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] onResume - VISIBLE to user, callId=${getCallId()}",
            data = mapOf("callId" to getCallId()))
    }

    override fun onPause() {
        super.onPause()
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] onPause - callId=${getCallId()}",
            SentryHelper.Level.WARNING,
            mapOf("callId" to getCallId()))
    }

    override fun onStop() {
        super.onStop()
        SentryHelper.log(TAG,
            "[CALLKIT_ACTIVITY] onStop - isFinishing=$isFinishing, callId=${getCallId()}",
            if (isFinishing) SentryHelper.Level.INFO else SentryHelper.Level.WARNING,
            mapOf("callId" to getCallId(), "isFinishing" to isFinishing))
    }

    override fun onBackPressed() {}
}
