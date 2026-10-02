package com.example.wy


object TrackedApps {
    val PACKAGES = setOf(
        "com.supercell.clashofclans",
        "com.dts.freefiremax",
        "com.garena.game.kgvn",
        "com.roblox.client",
        "com.riotgames.league.wildriftvn",
        "com.zhiliaoapp.musically",
        "com.facebook.katana"
    )
}

object GameSessions {
    fun key(pkg: String, startMilli: Long): String = "game:$pkg:$startMilli"
    fun closeSession(pkg: String, mark:List<UsageMark>, nowMilli: Long) :List<GameSession>{
        val order = mark.filter {
            it.packageName == pkg            && it.timeMillis            <=nowMilli
        }.sortedBy { it.timeMillis }

    val found= mutableListOf<GameSession>()
var openStart:Long? =null

      for (mrk in order ){
          when(mrk.type){
              UsageMark.Type.RESUME->if(openStart==null)openStart = mrk.timeMillis
              UsageMark.Type.PAUSE->{
                  val start = openStart?:continue
                  found+= GameSession  (pkg,start,mrk.timeMillis)
              openStart=null
              }
          }
      }
        return found
    }
}
data class GameSession(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis get() = endMillis - startMillis
}