package app.notube

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 삭제 방지. '기기 관리자'로 켜 두면 앱을 바로 지울 수 없고, 먼저 설정에서 이걸 꺼야 해요.
 * 휴대폰을 제어하는 권한은 하나도 쓰지 않아요 (정책 목록이 비어 있음).
 */
class GuardAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "삭제 방지를 끄면 노튜브 저금통을 지울 수 있게 돼요. 끈 기록은 앱에 남아요. " +
            "정말 필요할 때만 끄세요."

    override fun onDisabled(context: Context, intent: Intent) = record(context, "삭제 방지 꺼짐")

    override fun onEnabled(context: Context, intent: Intent) = record(context, "삭제 방지 켜짐")

    /** 저장은 메인 스레드를 막지 않게 따로 해요. */
    private fun record(context: Context, text: String) {
        val pending = goAsync()
        Thread {
            try { Repo.init(context); Repo.addNote("admin", text) } finally { pending.finish() }
        }.start()
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, GuardAdmin::class.java)

        fun isActive(ctx: Context): Boolean =
            (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(component(ctx))

        fun enableIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "켜 두면 노튜브 저금통을 바로 지울 수 없어요. 지우려면 먼저 이 설정을 꺼야 해서, " +
                    "충동적으로 앱을 지우고 다시 까는 걸 막아줘요. 휴대폰을 제어하는 권한은 쓰지 않아요.",
            )
    }
}
