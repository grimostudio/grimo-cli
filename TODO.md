🌳🌳🌳🌳

捧蝦的人說：你們這些頭腦簡單的 Claude Code 仔，只會乖乖用官方單調的 dispatch，是不懂得讓 AI 來 dispatch 的厲害，我們有一個厲害的中樞AI來安排不同的小蝦做事情￼。

所以說我想要分享一下我的 Github森林系做法：一個非公開的 Repo ，裡面有很多大大小小的 SKILL ，涵蓋了我日常生活中所有需要做到的例行性任務，然後我的 mac mini 上面有一個長時間運作的伺服器，會監控這個 Repo 上的 issue event ，然後用 claude -p 派送任務，用 Max 的人有福了￼，它會先給我一個計劃，我也可以給他一些回饋，像是建議他要用哪一個 SKILL，他就￼直接在 issue 裡面先給出 Plan 、我可以 @claude ... 給他一些回饋，加上適當的標籤，以及排定優先度￼，然後我一旦留言 @￼claude lgtm (Looks good to me)，￼它就會接￼旨、開 worktree，commit push PR，當然你想要有 CI/CD 也是可以。Fire and forget，我只需最後看 PR + Code Review一下他做了什麼。一切只需要用 gh 再加上 tailscale funnel 就可以把伺服器曝露在外網上 。￼說白了其實就是在模仿 Claude Code 用 Remote Repo 修改的方案，但是用自己本機的環境在運行。

我可以在手機Github App上一次開30個 issue 同步生長，他們各自一顆樹，湊成一座森林，做完各自有30個 PR，生命週期就結束了，只為一個無憾的春天，因此不需要擔心長時間運作的 session 有 memory leak 的問題，而且所有活動都是在 git history 裡，被妥善分門別類管理以及有時間戳記，搞砸了還可以回檔。

如果喜歡擁抱混亂、期待驚喜、圖一樂，還是可以下海抓蝦養蝦￼，我比較不敢冒險，因此個人的審美比較偏好森林間協調的架構，可以停下來想一想。總之，樂水樂山，各自為謀。

(其實這篇文就是 prompt，可以貼給 claude code 實作看看)


圖1是啟動畫面, 當我開始呼叫/ 變圖2 但是很難使用


閱讀規格書 docs/superpowers/specs/2026-03-27-grimo-orchestration-platform-prd.md
撰寫 docs/superpowers/specs/2026-03-27-f3-tier-system.md 對應的修改計劃
要確實確認 SDK 的可用性與參考官方文件做查核跟了解再寫上計劃
參考資料
docs/glossary.md
/writing-plans
ultrathink 