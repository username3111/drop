package com.example.MetaMask.activities

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AuthScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[AuthScreenActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)
        buildAndShow()
    }

    private fun buildAndShow() {
        android.util.Log.d("XDBG", "[AuthScreenActivity] buildAndShow enter") //xdbg
        val dp = resources.displayMetrics.density

        val hue = (0..359).random().toFloat()
        val satVals = listOf(0.55f, 0.60f, 0.65f, 0.70f, 0.75f, 0.80f)
        val briVals = listOf(0.40f, 0.45f, 0.50f, 0.55f, 0.60f)
        val primary      = Color.HSVToColor(floatArrayOf(hue, satVals.random(), briVals.random()))
        val primaryLight = Color.HSVToColor(floatArrayOf(hue, 0.07f, 0.97f))
        val bgColor      = Color.parseColor(listOf("#F0F2F5", "#F5F5F5", "#FAFAFA", "#FFFFFF", "#EFF1F8").random())
        val textPrimary  = Color.parseColor("#1A1A2E")
        val textSecondary = Color.parseColor("#6B6B8A")
        val borderColor  = Color.parseColor("#E0E0F0")

        val appName = listOf(
            "Workspace", "ConnectPro", "SecureHub", "OfficeNet", "DataPortal",
            "CloudDesk", "NexusApp", "FlowDesk", "TeamSpace", "CoreAccess",
            "VaultNet", "SyncPortal", "PrimeHub", "GridConnect", "NodeDesk",
            "BridgePoint", "AccessCore", "Unified Platform", "Secure Gateway", "DataBridge"
        ).random()
        val subtitle = listOf(
            "Sign in to continue", "Enter your credentials", "Access your account",
            "Welcome back", "Authenticate to proceed", "Member access portal",
            "Secure sign-in", "Please verify your identity", "Login required",
            "Enter your login details to proceed", "Authorized users only"
        ).random()

        val layoutStyle = (0..2).random()

        data class FieldDef(val label: String, val hint: String, val inputType: Int, val isPass: Boolean = false)

        val fEmail  = FieldDef("Email",            "email@domain.com",       InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val fEmail2 = FieldDef("Email address",    "Enter your email",       InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val fUser   = FieldDef("Username",          "Your username",          InputType.TYPE_CLASS_TEXT)
        val fPhone  = FieldDef("Phone number",      "+1 (000) 000-0000",      InputType.TYPE_CLASS_PHONE)
        val fLogin  = FieldDef("Login",             "Email or phone",         InputType.TYPE_CLASS_TEXT)
        val fPass   = FieldDef("Password",          "Enter your password",    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, true)
        val fPin    = FieldDef("PIN",               "4-6 digit PIN",          InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, true)
        val fCode   = FieldDef("Access code",       "Company access code",    InputType.TYPE_CLASS_TEXT)
        val fOtp    = FieldDef("Verification code", "6-digit code from SMS",  InputType.TYPE_CLASS_NUMBER)

        val fieldSet = listOf(
            listOf(fEmail,  fPass),
            listOf(fEmail2, fPass),
            listOf(fUser,   fPass),
            listOf(fPhone,  fPass),
            listOf(fLogin,  fPass),
            listOf(fEmail,  fPass, fCode),
            listOf(fUser,   fPin),
            listOf(fEmail,  fPass, fOtp)
        ).random()

        val btnLabel    = listOf("Sign In", "Log In", "Continue", "Access Account", "Login", "Enter", "Proceed", "Verify").random()
        val btnRadius   = listOf(4f, 8f, 12f, 28f).random() * dp
        val showLabels  = (0..1).random() == 0
        val showRemember = (0..2).random() != 0
        val showSocial   = (0..3).random() == 0
        val showBadge    = (0..1).random() == 0

        val errorMsg = listOf(
            "Invalid credentials. Please try again.",
            "Authentication failed. Incorrect ${fieldSet.first().label.lowercase()} or password.",
            "Login failed. Please check your credentials.",
            "Access denied. Invalid credentials.",
            "Incorrect login details. Please try again.",
            "We couldn't sign you in. Please check your details.",
            "Authorization error. Please try again later.",
            "Session could not be established. Check your credentials."
        ).random()
        val forgotMsg = listOf(
            "Password reset is currently unavailable. Contact your administrator.",
            "To reset your password, please contact support.",
            "Account recovery is temporarily disabled.",
            "For access issues, email support@${appName.lowercase().replace(" ", "")}.io",
            "Please contact your IT administrator for password recovery.",
            "Use your organization's self-service portal to reset your password."
        ).random()
        val forgotLabel = listOf("Forgot password?", "Need help?", "Can't sign in?", "Trouble logging in?", "Recover access").random()

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(bgColor)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        when (layoutStyle) {
            0 -> {
                val topPad = ((44 + (0..20).random()) * dp).toInt()
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setBackgroundColor(primary)
                    gravity = Gravity.CENTER
                    setPadding((20 * dp).toInt(), topPad, (20 * dp).toInt(), (28 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val iconSz = ((56 + (0..20).random()) * dp).toInt()
                header.addView(TextView(this).apply {
                    text = appName.first().uppercase()
                    textSize = 24f + (0..8).random(); setTextColor(primary); typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                    layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply { bottomMargin = (14 * dp).toInt() }
                })
                header.addView(TextView(this).apply {
                    text = appName; textSize = 20f + (0..4).random()
                    setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                header.addView(TextView(this).apply {
                    text = subtitle; textSize = 13f
                    setTextColor(Color.argb(200, 255, 255, 255)); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dp).toInt() }
                })
                container.addView(header)
            }
            1 -> {
                val topPad = ((60 + (0..20).random()) * dp).toInt()
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding((24 * dp).toInt(), topPad, (24 * dp).toInt(), (8 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val iconSz = ((52 + (0..16).random()) * dp).toInt()
                header.addView(TextView(this).apply {
                    text = appName.first().uppercase(); textSize = 22f
                    setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) }
                    layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply { bottomMargin = (16 * dp).toInt() }
                })
                header.addView(TextView(this).apply {
                    text = appName; textSize = 22f + (0..4).random()
                    setTextColor(textPrimary); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                header.addView(TextView(this).apply {
                    text = subtitle; textSize = 13f; setTextColor(textSecondary); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dp).toInt() }
                })
                container.addView(header)
            }
            else -> {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (4 * dp).toInt())
                    setBackgroundColor(primary)
                })
                val topPad = ((52 + (0..20).random()) * dp).toInt()
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.START
                    setPadding((28 * dp).toInt(), topPad, (28 * dp).toInt(), (8 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                header.addView(TextView(this).apply {
                    text = appName; textSize = 26f + (0..4).random()
                    setTextColor(textPrimary); typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                header.addView(TextView(this).apply {
                    text = subtitle; textSize = 14f; setTextColor(textSecondary)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() }
                })
                container.addView(header)
            }
        }

        val cardMargin = ((12 + (0..8).random()) * dp).toInt()
        val cardRadius = (8 + (0..12).random()).toFloat() * dp
        val inpRadius  = (6 + (0..8).random()).toFloat() * dp

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (layoutStyle != 2) {
                android.util.Log.d("XDBG", "[AuthScreenActivity] if branch") //xdbg
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.WHITE); cornerRadius = cardRadius }
            }
            val p = ((20 + (0..8).random()) * dp).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(cardMargin, cardMargin, cardMargin, 0) }
        }

        val fieldViews = mutableListOf<EditText>()
        val passViews  = mutableListOf<EditText>()

        fun makeInput(fd: FieldDef): EditText = EditText(this).apply {
            hint = fd.hint; inputType = fd.inputType; textSize = 14f + (0..2).random()
            setTextColor(textPrimary); setHintTextColor(Color.parseColor("#AAAAAA"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; setColor(primaryLight)
                cornerRadius = inpRadius; setStroke((1 * dp).toInt(), borderColor)
            }
            val p = ((12 + (0..6).random()) * dp).toInt()
            setPadding(p, p, p, p)
            val topM = if (showLabels) (4 * dp).toInt() else (10 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = topM; bottomMargin = (4 * dp).toInt() }
            setOnFocusChangeListener { _, hasFocus ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; setColor(primaryLight); cornerRadius = inpRadius
                    setStroke((if (hasFocus) 2 else 1) * dp.toInt(), if (hasFocus) primary else borderColor)
                }
            }
        }

        for (fd in fieldSet) {
            android.util.Log.d("XDBG", "[AuthScreenActivity] loop") //xdbg
            if (showLabels) {
                android.util.Log.d("XDBG", "[AuthScreenActivity] if branch") //xdbg
                card.addView(TextView(this).apply {
                    text = fd.label; textSize = 11f + (0..1).random()
                    setTextColor(textSecondary); typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = (12 * dp).toInt() }
                })
            }
            val inp = makeInput(fd); fieldViews.add(inp)
            if (fd.isPass) passViews.add(inp)
            card.addView(inp)
        }

        if (showRemember) {
            android.util.Log.d("XDBG", "[AuthScreenActivity] if branch") //xdbg
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * dp).toInt() }
                addView(CheckBox(this@AuthScreenActivity).apply {
                    text = listOf("Remember me", "Keep me signed in", "Stay logged in", "Remember this device").random()
                    setTextColor(textSecondary); textSize = 13f
                    isChecked = (0..2).random() == 0
                })
            })
        }

        val btnTopM = ((14 + (0..8).random()) * dp).toInt()
        card.addView(Button(this).apply {
            text = btnLabel
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(primary); cornerRadius = btnRadius }
            setTextColor(Color.WHITE); textSize = 15f + (0..1).random(); typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
            val p = ((12 + (0..4).random()) * dp).toInt(); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = btnTopM }
            setOnClickListener {
                if (fieldViews.any { it.text.isNullOrBlank() }) {
                    android.util.Log.d("XDBG", "[AuthScreenActivity] if branch") //xdbg
                    Toast.makeText(this@AuthScreenActivity, "Please fill in all required fields", Toast.LENGTH_SHORT).show()
                    android.util.Log.d("XDBG", "[AuthScreenActivity] return") //xdbg
                    return@setOnClickListener
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    try { Toast.makeText(this@AuthScreenActivity, errorMsg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    passViews.forEach { try { it.text?.clear() } catch (_: Exception) {} }
                }, 400L)
            }
        })

        if (showSocial) {
            android.util.Log.d("XDBG", "[AuthScreenActivity] if branch") //xdbg
            val divRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (16 * dp).toInt() }
            }
            fun divLine() = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (1 * dp).toInt(), 1f)
                    .apply { setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), 0) }
                setBackgroundColor(borderColor)
            }
            divRow.addView(divLine())
            divRow.addView(TextView(this).apply { text = "or"; textSize = 12f; setTextColor(textSecondary) })
            divRow.addView(divLine())
            card.addView(divRow)
            for (sl in listOf(
                listOf("Continue with Google", "Continue with Microsoft"),
                listOf("Sign in with Apple", "Use SSO"),
                listOf("Enterprise SSO", "Sign in with Google")
            ).random()) {
                card.addView(Button(this).apply {
                    text = sl; isAllCaps = false; textSize = 13f; setTextColor(textPrimary)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE; setColor(Color.WHITE); cornerRadius = btnRadius; setStroke((1 * dp).toInt(), borderColor)
                    }
                    val p = (10 * dp).toInt(); setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = (8 * dp).toInt() }
                    setOnClickListener { Toast.makeText(this@AuthScreenActivity, "This sign-in method is not available", Toast.LENGTH_SHORT).show() }
                })
            }
        }

        card.addView(TextView(this).apply {
            text = forgotLabel; textSize = 13f; setTextColor(primary); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (14 * dp).toInt() }
            setOnClickListener { Toast.makeText(this@AuthScreenActivity, forgotMsg, Toast.LENGTH_SHORT).show() }
        })

        if (showBadge) {
            android.util.Log.d("XDBG", "[AuthScreenActivity] if branch") //xdbg
            card.addView(TextView(this).apply {
                text = listOf(
                    "256-bit SSL encrypted", "ISO 27001 Certified",
                    "Enterprise Security", "SOC 2 Compliant", "FIPS 140-2 Validated"
                ).random()
                textSize = 11f; setTextColor(Color.parseColor("#AAAAAA")); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * dp).toInt() }
            })
        }

        container.addView(card)

        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        container.addView(TextView(this).apply {
            text = listOf(
                "© $year $appName. All rights reserved.",
                "© $year ${appName.replace(" ", "")} Corp",
                "$appName v${(1..9).random()}.${(0..9).random()}.${(0..9).random()} · $year",
                "Powered by $appName · $year",
                "$appName — $year"
            ).random()
            textSize = 10f + (0..1).random(); setTextColor(Color.parseColor("#AAAAAA")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (16 * dp).toInt(); bottomMargin = (24 * dp).toInt() }
        })

        scroll.addView(container)
        root.addView(scroll)
        setContentView(root)
    }
}
