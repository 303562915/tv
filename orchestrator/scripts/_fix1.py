# -*- coding: utf-8 -*-
import pathlib, re
p = pathlib.Path(r"G:\trae\tv\orchestrator\staging\F020\files\java\feature\f020\F020Activity.kt")
s = p.read_text(encoding="utf-8")
s = s.replace("import android.graphics.Color\n", "")
s = s.replace("import android.content.SharedPreferences\n",
              "import android.content.SharedPreferences\nimport android.view.ViewGroup\nimport android.widget.LinearLayout\nimport android.widget.Toast\nimport androidx.core.content.ContextCompat\n")
s = s.replace('setTextColor(Color.parseColor("#DDDDDD"))',
              'setTextColor(ContextCompat.getColor(context, R.color.theme_text_secondary))')
s = s.replace('val primary = Color.parseColor("#1A73E8")',
              'val primary = ContextCompat.getColor(this, R.color.theme_primary)')
p.write_text(s, encoding="utf-8")
print("F020 patched; hex left:", len(re.findall(r'#[0-9A-Fa-f]{6}', s)))

# F023: nullable receiver 上的 equalsIgnoreCase
q = pathlib.Path(r"G:\trae\tv\orchestrator\staging\F023\files\java\feature\f023\F023Engine.kt")
t = q.read_text(encoding="utf-8")
t = t.replace("normalizeDir(it).equalsIgnoreCase(normalized)",
              "normalizeDir(it)?.equals(normalized, ignoreCase = true) == true")
q.write_text(t, encoding="utf-8")
print("F023 patched")
