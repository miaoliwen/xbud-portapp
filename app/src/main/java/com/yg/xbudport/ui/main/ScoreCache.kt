package com.yg.xbudport.ui.main

// 进程级成绩缓存：主页进度 / 跑步记录列表一次拉取后全 App 复用，
// 重进页面直接渲染缓存、不再打网络（真机上的第二次加载曾是崩溃路径）。
//
// 失效时机（显式，不做 TTL——用户要求"不需要二次加载"，过期感知交给刷新按钮）：
// - 跑步提交成功（MainActivity 的 RunScreen onDone）：产生了新成绩
// - 会话失效 / 登出（MainActivity 各 onSessionExpired / store.clear() 处）：
//   缓存里的数据属于旧 token，不能给新会话用
internal object ScoreCache {
    var home: HomeUi? = null
    var history: HistoryUi? = null

    fun invalidate() {
        home = null
        history = null
    }
}
