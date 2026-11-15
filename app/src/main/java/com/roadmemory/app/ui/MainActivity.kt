package com.roadmemory.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.roadmemory.app.core.DrivingLoadEngine
import com.roadmemory.app.core.TripSession
import com.roadmemory.app.data.GearState
import com.roadmemory.app.data.TripMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car screen (landscape, glanceable). Three columns:
 *  left   – driving load + vehicle context (why the car is quiet or talking)
 *  centre – today's question / recall card and the few big buttons
 *  right  – this week's family feed, places and milestones
 */
class MainActivity : Activity() {

    private lateinit var session: TripSession
    private lateinit var demo: DemoScenario
    private var lastState: TripSession.UiState? = null
    private var started = false

    // views
    private lateinit var modeChip: TextView
    private lateinit var loadScore: TextView
    private lateinit var loadTier: TextView
    private lateinit var loadBar: ProgressBar
    private lateinit var loadReasons: TextView
    private lateinit var vehicleInfo: TextView
    private lateinit var cardLabel: TextView
    private lateinit var cardText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var statusText: TextView
    private lateinit var travelButton: Button
    private lateinit var demoButton: Button
    private lateinit var consentSwitch: Switch
    private lateinit var petSwitch: Switch
    private lateinit var feedHeader: TextView
    private lateinit var feedList: LinearLayout
    private lateinit var milestoneText: TextView

    private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = TripSession(this) { render(it) }
        demo = DemoScenario(session, { lastState?.phase ?: TripSession.Phase.IDLE }) {
            demoButton.text = "데모 주행 시작"
        }
        setContentView(buildLayout())
        requestNeededPermissions()
    }

    override fun onDestroy() {
        demo.cancel()
        if (started) session.stop()
        super.onDestroy()
    }

    // ---- permissions ---------------------------------------------------------------------------

    private fun requestNeededPermissions() {
        val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startSession() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) startSession()
    }

    private fun startSession() {
        if (started) return
        started = true
        session.start()
    }

    // ---- layout --------------------------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(28), dp(20), dp(28), dp(16))
        }

        // header
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("로드메모리", 30f, TEXT, bold = true))
        header.addView(text("  길 위의 가족 일기", 18f, MUTED))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        modeChip = text("일상 모드", 18f, BG, bold = true).apply {
            background = rounded(ACCENT, 40)
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        header.addView(modeChip)
        root.addView(header)

        val columns = row().apply { setPadding(0, dp(16), 0, dp(12)) }
        columns.addView(leftColumn(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        columns.addView(space(dp(18)))
        columns.addView(centerColumn(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f))
        columns.addView(space(dp(18)))
        columns.addView(rightColumn(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        root.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(bottomBar())
        return root
    }

    private fun leftColumn(): View = card().apply {
        addView(text("운전 부담", 16f, MUTED))
        val scoreRow = row().apply { gravity = Gravity.BOTTOM }
        loadScore = text("0", 56f, TEXT, bold = true)
        loadTier = text("  여유", 22f, GREEN, bold = true)
        scoreRow.addView(loadScore)
        scoreRow.addView(loadTier)
        addView(scoreRow)
        loadBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        addView(loadBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))
        loadReasons = text("-", 15f, MUTED).apply { setPadding(0, dp(8), 0, dp(18)) }
        addView(loadReasons)
        addView(text("차량 맥락", 16f, MUTED))
        vehicleInfo = text("", 18f, TEXT).apply { setLineSpacing(dp(6).toFloat(), 1f) }
        addView(vehicleInfo)
    }

    private fun centerColumn(): View = card().apply {
        cardLabel = text("오늘의 한 질문", 18f, ACCENT, bold = true)
        addView(cardLabel)
        cardText = text("주행을 시작하면, 여유가 있을 때 한 가지만 물어볼게요.", 30f, TEXT, bold = true).apply {
            setPadding(0, dp(12), 0, dp(12))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        addView(cardText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        transcriptText = text("", 20f, MINT).apply { setPadding(0, 0, 0, dp(8)) }
        addView(transcriptText)
        statusText = text("", 16f, MUTED).apply { setPadding(0, 0, 0, dp(12)) }
        addView(statusText)

        val buttons = row()
        buttons.addView(bigButton("지금 질문") { session.askNow() }, weight())
        buttons.addView(space(dp(12)))
        travelButton = bigButton("여행 모드") { session.toggleTravelMode() }
        buttons.addView(travelButton, weight())
        buttons.addView(space(dp(12)))
        buttons.addView(bigButton("도착 처리") { session.endTripManually() }, weight())
        addView(buttons)
    }

    private fun rightColumn(): View = card().apply {
        feedHeader = text("이번 주 가족 피드", 18f, TEXT, bold = true)
        addView(feedHeader)
        feedList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this@MainActivity).apply { addView(feedList) }
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        milestoneText = text("", 15f, ACCENT).apply { setPadding(0, dp(8), 0, 0) }
        addView(milestoneText)
    }

    private fun bottomBar(): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        consentSwitch = Switch(this@MainActivity).apply {
            text = "가족 기록 동의  "
            textSize = 17f
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, checked ->
                if (lastState?.consent != checked) session.setConsent(checked)
            }
        }
        addView(consentSwitch)
        petSwitch = Switch(this@MainActivity).apply {
            text = "  반려견 동승  "
            textSize = 17f
            setTextColor(TEXT)
            setOnCheckedChangeListener { _, checked ->
                if (lastState?.petOnBoard != checked) session.setPetOnBoard(checked)
            }
        }
        addView(petSwitch)
        addView(text("  원문 7일 후 자동 삭제", 15f, MUTED))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        demoButton = smallButton("데모 주행 시작") {
            if (demo.running) {
                demo.cancel()
            } else {
                demoButton.text = "데모 중지"
                demo.start()
            }
        }
        addView(demoButton)
        addView(space(dp(10)))
        addView(smallButton("예시 답변") { session.submitAnswer(DemoScenario.SAMPLE_ANSWER) })
        addView(space(dp(10)))
        addView(smallButton("기록 전체 삭제") {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("기록 전체 삭제")
                .setMessage("장소, 주행, 순간 기록이 모두 지워지며 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ -> session.clearAll() }
                .setNegativeButton("취소", null)
                .show()
        })
    }

    // ---- render --------------------------------------------------------------------------------

    private fun render(s: TripSession.UiState) {
        lastState = s
        if (!::modeChip.isInitialized) return

        modeChip.text = if (s.mode == TripMode.TRAVEL) "여행 모드" else "일상 모드"
        modeChip.background = rounded(if (s.mode == TripMode.TRAVEL) MINT else ACCENT, 40)
        travelButton.text = if (s.mode == TripMode.TRAVEL) "일상 모드로" else "여행 모드"

        val tierColor = when (s.load.tier) {
            DrivingLoadEngine.Tier.FREE -> GREEN
            DrivingLoadEngine.Tier.LIGHT -> ACCENT
            DrivingLoadEngine.Tier.BUSY -> RED
        }
        loadScore.text = s.load.score.toString()
        loadTier.text = when (s.load.tier) {
            DrivingLoadEngine.Tier.FREE -> "  여유 · 대화 가능"
            DrivingLoadEngine.Tier.LIGHT -> "  보통 · 짧은 안내만"
            DrivingLoadEngine.Tier.BUSY -> "  집중 · 침묵"
        }
        loadTier.setTextColor(tierColor)
        loadBar.progress = s.load.score
        loadBar.progressTintList = android.content.res.ColorStateList.valueOf(tierColor)
        loadReasons.text = if (s.load.reasons.isEmpty()) "부담 요인 없음" else s.load.reasons.joinToString(" · ")

        val v = s.vehicle
        val gear = when (v.gear) {
            GearState.PARK -> "P"
            GearState.DRIVE -> "D"
            GearState.REVERSE -> "R"
            GearState.NEUTRAL -> "N"
            GearState.UNKNOWN -> "-"
        }
        val guidance = s.navi.nextGuidanceMeters?.let { "${it.toInt()}m ${s.navi.nextGuidanceText ?: ""}" } ?: "-"
        vehicleInfo.text = buildString {
            appendLine("기어  $gear      속도  ${s.location.speedKmh.toInt()} km/h")
            appendLine("탑승  운전자 + ${v.passengerCount}명${if (v.hasRearPassenger) " (뒷좌석)" else ""}")
            appendLine("와이퍼  ${if (v.wiperOn) "작동" else "꺼짐"}      브레이크  ${if (v.brakePressed) "밟음" else "-"}")
            appendLine("목적지  ${s.navi.destinationName ?: "-"}")
            append("다음 안내  $guidance")
        }

        cardLabel.text = when (s.phase) {
            TripSession.Phase.LISTENING -> "듣고 있어요"
            TripSession.Phase.SAVING -> "저장 중"
            else -> if (s.status == "같은 길 회상") "같은 길 회상" else "오늘의 한 질문"
        }
        s.question?.let { cardText.text = it }
        transcriptText.text = s.transcript?.let { "“$it”" } ?: ""
        statusText.text = s.status + if (s.paused) "  (기록 일시정지)" else ""

        if (consentSwitch.isChecked != s.consent) consentSwitch.isChecked = s.consent
        if (petSwitch.isChecked != s.petOnBoard) petSwitch.isChecked = s.petOnBoard

        feedHeader.text = "이번 주 가족 피드 · ${s.weekCount}개 · 장소 ${s.placeCount}곳"
        feedList.removeAllViews()
        if (s.recent.isEmpty()) {
            feedList.addView(text("아직 남긴 순간이 없어요.", 16f, MUTED))
        }
        s.recent.forEach { m ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(CARD2, 14)
            }
            item.addView(text("${timeFormat.format(Date(m.createdAt))}  ${m.tags.joinToString(" ") { "#$it" }}", 13f, MUTED))
            item.addView(text(m.summary, 17f, TEXT))
            item.addView(text("Q. ${m.question}", 13f, MUTED))
            feedList.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        milestoneText.text = s.milestones.joinToString("\n") { "★ ${it.title}" }
    }

    // ---- small view helpers --------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun weight() = LinearLayout.LayoutParams(0, dp(72), 1f)

    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 24)
        setPadding(dp(22), dp(20), dp(22), dp(20))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setTextColor(TEXT)
        background = rounded(CARD2, 18)
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(TEXT)
        background = rounded(CARD, 14)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    companion object {
        private const val REQ_PERMS = 7
        private val PERMISSIONS = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            "pleos.car.permission.FUSED_LOCATION",
            "pleos.car.permission.TTS_SERVICE",
            "pleos.car.permission.STT_SERVICE",
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_INFO",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CAR_WINDOWS",
            "pleos.car.permission.CAR_SEATS",
            "pleos.car.permission.CAR_SAFETY",
        )

        private val BG = Color.parseColor("#0E1116")
        private val CARD = Color.parseColor("#171C24")
        private val CARD2 = Color.parseColor("#222935")
        private val TEXT = Color.parseColor("#F1F3F6")
        private val MUTED = Color.parseColor("#8A94A6")
        private val ACCENT = Color.parseColor("#F2B84B")
        private val MINT = Color.parseColor("#7FD1B9")
        private val GREEN = Color.parseColor("#5BD18B")
        private val RED = Color.parseColor("#FF6B6B")
    }
}
