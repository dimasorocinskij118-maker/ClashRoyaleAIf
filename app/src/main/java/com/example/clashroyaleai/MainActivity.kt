package com.example.clashroyaleai

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var arena: ArenaView
    private lateinit var stats: TextView
    private lateinit var runButton: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ai", Context.MODE_PRIVATE)
        val learner = Learner(prefs)
        arena = ArenaView(this, learner)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(Color.rgb(20, 39, 34))
        }
        val title = TextView(this).apply {
            text = "CLASH ROYALE AI  •  SIMULATOR"
            textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER; setPadding(0, 8, 0, 12)
        }
        stats = TextView(this).apply {
            textSize = 13f; setTextColor(Color.rgb(230, 239, 229)); gravity = android.view.Gravity.CENTER
            setPadding(4, 8, 4, 8)
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runButton = Button(this).apply { text = "▶ СТАРТ"; setOnClickListener {
            arena.running = !arena.running
            text = if (arena.running) "Ⅱ ПАУЗА" else "▶ ПРОДОЛЖИТЬ"
        } }
        val reset = Button(this).apply { text = "↻ НОВЫЙ БОЙ"; setOnClickListener { arena.resetBattle() } }
        val wipe = Button(this).apply { text = "СБРОСИТЬ ОБУЧЕНИЕ"; setOnClickListener {
            learner.clear(); arena.resetBattle(); Toast.makeText(this@MainActivity, "Память ИИ очищена", Toast.LENGTH_SHORT).show()
        } }
        controls.addView(runButton, LinearLayout.LayoutParams(0, 52, 1f))
        controls.addView(reset, LinearLayout.LayoutParams(0, 52, 1f))
        layout.addView(title)
        layout.addView(arena, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(stats)
        layout.addView(controls)
        layout.addView(wipe, LinearLayout.LayoutParams(-1, 48))
        setContentView(layout)
        arena.onStats = { s -> runOnUiThread { stats.text = s } }
    }
}

private data class Unit(var x: Float, var y: Float, var hp: Float, val maxHp: Float, val side: Int, val kind: Int, var cooldown: Float = 0f)

private class Learner(private val prefs: android.content.SharedPreferences) {
    // Tabular Q-learning: state = elixir band × threat band × tower health band.
    private val q = HashMap<String, FloatArray>()
    var episodes = prefs.getInt("episodes", 0); private set
    var wins = prefs.getInt("wins", 0); private set
    var decisions = prefs.getInt("decisions", 0); private set
    private var lastKey: String? = null
    private var lastAction = 0
    private var lastReward = 0f
    private val alpha = 0.18f
    private val gamma = 0.88f
    private val epsilon: Float get() = max(0.06f, 0.42f - episodes * 0.002f)

    fun choose(elixir: Int, threat: Int, ownHp: Int, enemyHp: Int): Int {
        val key = state(elixir, threat, ownHp, enemyHp)
        val values = q.getOrPut(key) { FloatArray(4) }
        val action = if (Random.nextFloat() < epsilon) Random.nextInt(4) else values.indices.maxBy { values[it] }
        lastKey = key; lastAction = action; decisions++
        return action // 0 wait, 1 melee, 2 ranged, 3 defend
    }
    fun learn(reward: Float, terminal: Boolean, elixir: Int, threat: Int, ownHp: Int, enemyHp: Int) {
        val key = lastKey ?: return
        val old = q.getOrPut(key) { FloatArray(4) }
        val next = q.getOrPut(state(elixir, threat, ownHp, enemyHp)) { FloatArray(4) }
        val target = reward + if (terminal) 0f else gamma * (next.maxOrNull() ?: 0f)
        old[lastAction] += alpha * (target - old[lastAction])
        lastReward = reward
        if (terminal) {
            episodes++
            if (reward > 0) wins++
            prefs.edit().putInt("episodes", episodes).putInt("wins", wins).putInt("decisions", decisions).apply()
            lastKey = null
        }
    }
    private fun state(e: Int, t: Int, own: Int, enemy: Int) =
        "${e.coerceIn(0,9)/3}_${t.coerceIn(0,5)/2}_${own.coerceIn(0,100)/25}_${enemy.coerceIn(0,100)/25}"
    fun clear() { q.clear(); episodes=0; wins=0; decisions=0; lastKey=null
        prefs.edit().clear().apply() }
    fun summary() = "Эпизоды: $episodes   Победы: $wins   Решения: $decisions   ε: %.2f".format(epsilon)
}

private class ArenaView(context: Context, private val ai: Learner) : View(context) {
    var running = false
    var onStats: ((String) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val units = mutableListOf<Unit>()
    private var ownTower = 100f
    private var enemyTower = 100f
    private var elixir = 5f
    private var time = 180f
    private var tick = 0
    private var reward = 0f
    private var message = "Нажми СТАРТ — ИИ начнёт самостоятельный бой"
    private var lastFrame = System.currentTimeMillis()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        drawArena(c, w, h)
        if (running) {
            val now = System.currentTimeMillis()
            val dt = min(0.06f, (now-lastFrame)/1000f)
            lastFrame = now
            repeat(2) { step(0.025f) }
            postInvalidateDelayed(35)
        } else lastFrame = System.currentTimeMillis()
        onStats?.invoke("${ai.summary()}\\nБашни: ${ownTower.toInt()}% / ${enemyTower.toInt()}%   Эликсир: ${elixir.toInt()}   Время: ${time.toInt()}с")
    }

    private fun drawArena(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(42, 105, 75))
        val mid = h * .5f
        paint.color = Color.rgb(67, 130, 83); c.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.rgb(48, 91, 68); c.drawRect(0f, mid-dp(18f), w, mid+dp(18f), paint)
        paint.color = Color.rgb(188, 157, 105); c.drawRect(w*.44f, mid-dp(23f), w*.56f, mid+dp(23f), paint)
        paint.color = Color.rgb(40, 125, 190); c.drawRect(0f, mid-dp(5f), w, mid+dp(5f), paint)
        // Towers
        drawTower(c, w*.5f, h*.12f, Color.rgb(210, 75, 66), enemyTower, "ВРАГ")
        drawTower(c, w*.5f, h*.88f, Color.rgb(66, 132, 218), ownTower, "ТЫ")
        units.forEach {
            paint.color = if (it.side == 0) Color.rgb(75, 155, 255) else Color.rgb(240, 91, 80)
            c.drawCircle(it.x*w, it.y*h, dp(if(it.kind==2) 9f else 11f), paint)
            paint.color = Color.WHITE; paint.textSize = dp(9f)
            c.drawText(if(it.kind==2) "R" else "M", it.x*w-3f, it.y*h+3f, paint)
            paint.color = Color.BLACK; c.drawRect(it.x*w-dp(12f), it.y*h-dp(17f), it.x*w+dp(12f), it.y*h-dp(14f), paint)
            paint.color = Color.GREEN; c.drawRect(it.x*w-dp(12f), it.y*h-dp(17f), it.x*w-dp(24f)*(-it.hp/it.maxHp), it.y*h-dp(14f), paint)
        }
        paint.color = Color.WHITE; paint.textSize = dp(13f); paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText("ЭЛИКСИР  ${elixir.toInt()}/10", dp(12f), dp(24f), paint)
        paint.textSize = dp(12f); c.drawText(message, dp(12f), h-dp(14f), paint)
    }
    private fun drawTower(c: Canvas, x: Float, y: Float, color: Int, hp: Float, label: String) {
        paint.color=color; c.drawRoundRect(x-dp(27f), y-dp(23f), x+dp(27f), y+dp(23f), dp(7f), dp(7f), paint)
        paint.color=Color.WHITE; paint.textSize=dp(11f); paint.textAlign=Paint.Align.CENTER
        c.drawText(label, x, y+dp(4f), paint); paint.textAlign=Paint.Align.LEFT
        paint.color=Color.DKGRAY; c.drawRect(x-dp(28f), y+dp(29f), x+dp(28f), y+dp(34f), paint)
        paint.color=Color.GREEN; c.drawRect(x-dp(28f), y+dp(29f), x-dp(28f)+dp(56f)*hp/100f, y+dp(34f), paint)
    }

    private fun step(dt: Float) {
        if (ownTower<=0 || enemyTower<=0 || time<=0) { finish(); return }
        time-=dt; elixir=min(10f, elixir+dt*.42f); tick++
        units.forEach { u ->
            u.cooldown-=dt
            val dir=if(u.side==0) -1f else 1f
            val target=units.filter { it.side!=u.side }.minByOrNull { kotlin.math.abs(it.y-u.y) }
            if (target!=null && kotlin.math.abs(target.y-u.y)<.12f) {
                if(u.cooldown<=0) { target.hp-=if(u.kind==2) 8f else 13f; u.cooldown=.7f }
            } else {
                u.y=(u.y+dir*dt*.075f).coerceIn(.12f,.88f)
                val nearTower=if(u.side==0) u.y<.19f else u.y>.81f
                if(nearTower && u.cooldown<=0) {
                    if(u.side==0) enemyTower=max(0f,enemyTower-2.2f) else ownTower=max(0f,ownTower-2.2f)
                    u.cooldown=.8f
                }
            }
        }
        units.removeAll { it.hp<=0 || it.y<.105f || it.y>.895f }
        // Opponent adapts its pressure to current elixir; intentionally lightweight baseline policy.
        if(Random.nextFloat()<dt*.20f && units.count { it.side==1 }<4) {
            units.add(Unit(Random.nextFloat()*.2f+.4f,.24f,42f,42f,1,if(Random.nextBoolean()) 1 else 2))
        }
        if(tick%16==0) {
            val threat=units.count { it.side==1 && it.y>.55f }
            val action=ai.choose(elixir.toInt(),threat,ownTower.toInt(),enemyTower.toInt())
            when(action) {
                1 -> if(elixir>=3) { units.add(Unit(.5f,.78f,55f,55f,0,1)); elixir-=3; message="ИИ: выпускает бойца" }
                2 -> if(elixir>=4) { units.add(Unit(.5f,.72f,35f,35f,0,2)); elixir-=4; message="ИИ: выпускает стрелка" }
                3 -> if(elixir>=2) { units.add(Unit(.5f,.67f,65f,65f,0,1)); elixir-=2; message="ИИ: выбирает защиту" }
                else -> message="ИИ: копит эликсир"
            }
            val shaped=(enemyTower-ownTower)*.012f - threat*.025f + .01f
            ai.learn(shaped,false,elixir.toInt(),threat,ownTower.toInt(),enemyTower.toInt())
        }
        if(ownTower<=0 || enemyTower<=0 || time<=0) finish()
    }
    private fun finish() {
        running=false
        val won=enemyTower>ownTower
        val r=if(won) 10f else -10f
        ai.learn(r,true,elixir.toInt(),0,ownTower.toInt(),enemyTower.toInt())
        message=if(won) "ПОБЕДА! ИИ обновил стратегию" else "БОЙ ОКОНЧЕН — ИИ учтёт результат"
        invalidate()
    }
    fun resetBattle() {
        running=false; units.clear(); ownTower=100f; enemyTower=100f; elixir=5f; time=180f; tick=0
        message="Новый бой готов. Нажми СТАРТ"; invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
