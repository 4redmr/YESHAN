package app.notube

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 감시 서비스가 시스템에 의해 꺼졌을 때를 대비한 안전장치.
 * 15분마다 사용 기록을 정산하고(위젯도 갱신돼요), 가능하면 감시 서비스를 다시 켜요.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Repo.init(applicationContext)
        Repo.sync()
        Repo.checkProtection()
        NotionSync.syncIfDue(applicationContext, Repo.state.value)
        GuardService.start(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
