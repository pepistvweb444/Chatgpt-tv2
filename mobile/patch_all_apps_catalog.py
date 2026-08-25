from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s = p.read_text()

if 'import android.content.pm.LauncherApps' not in s:
    s = s.replace('import android.content.pm.PackageManager\n', 'import android.content.pm.LauncherApps\nimport android.content.pm.PackageManager\n')
if 'import android.os.Process' not in s:
    s = s.replace('import androidx.appcompat.app.AlertDialog\n', 'import android.os.Process\nimport androidx.appcompat.app.AlertDialog\n')

start = s.find('    private fun openApp(name: String): Boolean {')
end = s.find('    private fun plan(', start)
if start < 0 or end < 0:
    raise SystemExit('openApp block not found')

replacement = r'''    private fun openApp(name: String): Boolean {
        val wanted = canonical(name)
        if (wanted.isBlank()) return false
        val pm = activity.packageManager

        // Small compatibility shortcuts only; normal discovery below handles all apps.
        val knownPackages = mapOf(
            "glovo" to listOf("com.glovo"),
            "amazon" to listOf("com.amazon.mShop.android.shopping")
        )
        knownPackages[wanted]?.forEach { if (launchPackage(it)) return true }

        data class Candidate(val score: Int, val label: String, val packageName: String, val className: String?)
        val candidates = mutableListOf<Candidate>()

        // MAIN/LAUNCHER catalogue available through PackageManager.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL).forEach { ri ->
            val label = canonical(ri.loadLabel(pm)?.toString().orEmpty())
            val pkg = ri.activityInfo.packageName
            val tail = canonical(pkg.substringAfterLast('.'))
            val score = when {
                label == wanted -> 0
                label.contains(wanted) || wanted.contains(label) -> 1
                tail == wanted || tail.contains(wanted) -> 2
                else -> 3 + distance(label.take(32), wanted.take(32))
            }
            candidates += Candidate(score, label, pkg, ri.activityInfo.name)
        }

        // LauncherApps is a second source for the current user's actual launcher apps.
        runCatching {
            val launcherApps = activity.getSystemService(LauncherApps::class.java)
            launcherApps.getActivityList(null, Process.myUserHandle()).forEach { info ->
                val label = canonical(info.label?.toString().orEmpty())
                val pkg = info.applicationInfo.packageName
                val tail = canonical(pkg.substringAfterLast('.'))
                val score = when {
                    label == wanted -> 0
                    label.contains(wanted) || wanted.contains(label) -> 1
                    tail == wanted || tail.contains(wanted) -> 2
                    else -> 3 + distance(label.take(32), wanted.take(32))
                }
                candidates += Candidate(score, label, pkg, info.componentName.className)
            }
        }

        val best = candidates.distinctBy { it.packageName to it.className }.minByOrNull { it.score } ?: return false
        if (!(best.score <= 2 || distance(best.label, wanted) <= 2)) return false
        if (launchPackage(best.packageName)) return true
        val cls = best.className ?: return false
        val explicit = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(best.packageName, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activity.runOnUiThread { activity.startActivity(explicit) }; true }.getOrDefault(false)
    }

'''
s = s[:start] + replacement + s[end:]
p.write_text(s)
print('Universal installed-app catalogue enabled')
