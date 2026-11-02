# 11 · 多人协作与 Git 提交规范

> **这份文件解决一个问题**：两台（或多台）电脑同时改同一个工程时，把三件事说死——
> 改动推到哪儿、提交信息怎么写、撞车了谁让谁。看完这一页就能直接开工，不用再问人。
>
> 一句话版本：**开工先 `pull`，收工再 `push`，每次提交只做一件事，构建同一时间只有一台机器跑。**

---

## 1. 三分钟上手

日常只有四条命令，全部在项目根目录执行：

```bash
git pull share main                                  # ① 开工前，拿别人的改动
git add -A && git commit -m "feat: 做了什么"          # ② 干完活，存到本地
git push share main                                  # ③ 推给团队
```

第一次接手工程的人，先跳到第 3 节把 `share` 这个远程接上。

---

## 2. 仓库拓扑：三个位置，别搞混

| 位置 | 地址 | 是什么 | 谁往里写 |
| --- | --- | --- | --- |
| **本地工作区** | `D:\AndroidWork\SeAI_Encourage` | 你正在改的代码，编译也在这里 | 你自己 |
| **团队共享库**（主线） | `S:\git\SeAI_Encourage.git`<br>（`\\192.168.0.123\share\git\SeAI_Encourage.git`） | 局域网里那台 Ubuntu 上的裸仓库，不带工作区，只存提交历史 | 所有协作者 |
| **GitHub 备份** | `https://github.com/lgungb/MobileAI-local.git` | 对外的远程备份，断网/换环境时用来恢复 | 由主线定期同步 |

**规则只有一条：日常同步一律走 `share`，`origin` 只当备份。**

- `share` = 团队的黑板，谁写完都往黑板上贴，别人抬头就能看见。
- `origin` = 把黑板拍照存档发到网上，网断了也不影响本地干活。

工程里已经配好两个远程：

```bash
git remote -v
# share   S:/git/SeAI_Encourage.git             (fetch/push)  ← 团队主线
# origin  https://github.com/lgungb/MobileAI-local.git       ← 对外备份
```

> 在共享库里**不要**用资源管理器直接改文件。那边没有工作区，动了只会让仓库坏掉。

---

## 3. 新电脑接入（一次性，做完就不管了）

### 3.1 先能把共享文件夹打开

Windows 资源管理器地址栏直接输入：

```
\\192.168.0.123\share
```

账号 `lin`、密码 `123`（勾选"记住凭据"）。

### 3.2 映射成盘符（推荐，路径短、命令好写）

PowerShell 执行一次，`/persistent:yes` 表示重启后自动恢复：

```powershell
net use S: "\\192.168.0.123\share" /user:lin "123" /persistent:yes
```

之后在资源管理器左侧就能看到 **S 盘**，打开 `S:\git\SeAI_Encourage.git` 即是团队仓库本体。

### 3.3 把项目接到共享库

**情况 A：本地已经有项目目录**（当前这台机器就是这种情况）

```bash
cd D:/AndroidWork/SeAI_Encourage
git remote add share "S:/git/SeAI_Encourage.git"
git fetch share
git log --oneline -1 share/main        # 确认能读到
```

**情况 B：全新电脑，什么都没有**

```bash
git clone "S:/git/SeAI_Encourage.git" D:/AndroidWork/SeAI_Encourage
cd D:/AndroidWork/SeAI_Encourage
```

### 3.4 验证是否接好

```bash
git remote -v          # 能看到 share 两行
git fetch share        # 不报错
git status             # 工作区干净
```

> **为什么用盘符路径而不是 SSH**：SSH 地址 `ssh://lin@192.168.0.123/home/lin/share/git/SeAI_Encourage.git`
> 也能用，但每台机器要先配免密密钥，否则每次 push 都弹密码。用 S 盘不需要密钥、不需要额外配置，
> 而且顺手就能在资源管理器里看到仓库。**代价见第 7 节**：网络盘上的 git 偶发锁文件冲突。

---

## 4. 提交方案（分支 · 信息 · 版本号）

### 4.1 分支怎么用

| 分支 | 用途 | 规矩 |
| --- | --- | --- |
| `main` | 唯一长期主线 | 永远可构建；小改动（改文案、修 bug、补文档）直接提交 |
| `feat/xxx`、`fix/xxx` | 大改或试验性改动 | 数据层重构、换推理引擎这类，单独开分支，验证通过再合回 `main` |
| `publish-page` | 发布页专用，与主线无关 | **不要动**，也不要合进 `main` |

判断标准很简单：**这次改动如果要花半天以上、或者中途可能推倒重来，就开分支；否则直接上 `main`。**

### 4.2 提交信息格式

```
<类型>: <一句中文说明>
```

| 类型 | 用在什么情况 | 例子 |
| --- | --- | --- |
| `feat` | 新功能 | `feat: 本地 API 服务支持锁定模型（v67）` |
| `fix` | 修 bug | `fix: 修掉状态文案里的 %1$d 字面量` |
| `docs` | 只改文档 | `docs: 补 DSH 手机版实机观察（07 §11）` |
| `refactor` | 改结构不改行为 | `refactor: 会话列表拆出 ProfileCard` |
| `perf` | 只改性能 | `perf: 模型列表避免重复 preProcess` |
| `build` | 构建脚本 / 依赖 / 版本号 | `build: Gradle 内存上限下调到 1536m` |
| `chore` | 杂项 | `chore: 清理临时日志` |
| `test` | 测试相关 | `test: 补会话删除的绑定校验用例` |

三条硬规矩：

1. **一句话说清"改了什么"**，禁止写"优化了代码""修了一些问题"。半年后回来看要能懂。
2. **一次提交只做一件事**。文档和代码可以同一次提交，但纯文档改动单独提，别和功能混在一起。
3. **修 bug 的提交写症状**，不要写"修了个 bug"——症状是以后能搜到的关键词。

### 4.3 版本号纪律

> 依据：`00_索引.md` 「给 AI / 新同事的协作约定」第 5 条。

- 每完成一次**可构建**的提交：`versionCode +1`、`versionName` 末位 +1（如 `1.0.18 → 1.0.19`）。
  位置：`app/build.gradle.kts`。目的是**APK 版本号与提交一一对应**，装机后能反查是哪次代码。
- **纯文档 / 纯配置提交不升版本**（不产 APK，没有版本可言）。
- 稳定基线打 tag 锚定，tag 记得单独推：

```bash
git tag -a v1.0.29 -m "稳定基线：会话列表改造完成"
git push share --tags
```

### 4.4 文档纪律

- **改任何 doc 都要在 `10_文档变更日志.md` 加一行**（倒序），写清"改了什么 + 依据"，
  依据优先写 commit 号，其次写代码文件。禁止只写"优化了文档"。
- **判断某功能到底做没做，只认 `09_项目现状事实.md`**。其他文档会滞后，`04` 尤其不可信。

---

## 5. 日常协作流程

### 5.1 单人节奏（推荐，90% 的情况）

```
拉最新 → 干活 → 构建验证 → 提交 → 推送
```

```bash
git pull share main                                # ① 拉
# ... 改代码 ...
./gradlew.bat assembleRelease --offline            # ② 构建（内存已限额，见 6.1）
git add -A                                         # ③ 提交
git commit -m "feat: 某某功能（v68）"
git push share main                                # ④ 推
```

### 5.2 两个人同时在改（关键部分）

**① 开工一定先拉。** 这是唯一能避免 80% 冲突的动作，不拉就是等着撞车。

```bash
git pull share main
```

**② 推送被拒（`non-fast-forward`）不要慌，也不要强推。**

```bash
git pull --rebase share main     # 把你的提交接到别人后面
# 如果这一步提示冲突，见 5.3
git push share main
```

**③ 三条禁令：**

| 禁止 | 原因 |
| --- | --- |
| `git push --force` | 会直接抹掉对方已经推上来的提交，且本地找不回来 |
| `git reset --hard` | 同上，未推送的改动一并蒸发 |
| 在 `main` 上 `git rebase` 已推送的提交 | 会把别人的历史改成另一条线，所有人对不上号 |

### 5.3 撞车了怎么处理

冲突只会出现在**同一个人改同一批行**的时候。处理顺序：

1. `git status` 看清哪些文件冲突（`both modified`）。
2. 打开文件，找 `<<<<<<<` / `=======` / `>>>>>>>` 三段标记，**留下正确的内容，删掉标记本身**。
3. `git add <文件>` → `git rebase --continue`（或 `git commit`）。
4. **拿不准就停手**，把冲突文件发给对方看一眼再定。宁可慢五分钟，不要改错一行。

**几个高频冲突文件的处理约定**（提前说死，省得吵）：

| 文件 | 为什么常撞 | 怎么定 |
| --- | --- | --- |
| `app/build.gradle.kts` | 每次提交都要动版本号 | **取较大的那个版本号**，不要两行都留 |
| `doc/10_文档变更日志.md` | 都往最顶部加一条 | **两条都保留**，按时间新旧排序 |
| `gradle.properties` | 内存参数被各自调过 | 以**当前文件为准**，不要调回大内存（见 6.1） |
| `doc/09_项目现状事实.md` | 都在改状态表 | 逐条比对，取"更新的那条"，合并后回读一遍 |

### 5.4 分支合回主线

```bash
git checkout main
git pull share main
git merge --no-ff feat/xxx       # 保留分支痕迹，方便回溯
git push share main
git branch -d feat/xxx           # 合完删掉，别留一堆死分支
```

---

## 6. 构建与机器的约定（本项目特有，务必遵守）

### 6.1 同一时间只有一台机器能构建

Gradle 守护进程之间会抢文件锁，两台机器同时构建（或同一台机器开多个构建）的表现是：
**日志停在半路、没有 `BUILD SUCCESSFUL` 也没有 `FAILED`、进程悄悄消失**。踩过一次，积压了 5 个僵尸 daemon。

- 要构建前先说一声"我在构建"，别人等。
- 构建命令固定带 `--offline`：`./gradlew.bat assembleRelease --offline`
- **构建结束后立刻 `./gradlew.bat --stop`**，守护进程不会自己及时退，常驻照样吃内存。
- **不要删** `~/.gradle/caches` 和 `~/.gradle/wrapper/dists`，重下要很久。

### 6.2 内存上限不要上调

`gradle.properties` 已做过两次下调：`-Xmx1536m`、Kotlin daemon `-Xmx1024m`、`workers.max=1`。
注意 **Kotlin 守护进程和 Gradle 守护进程是两个独立 JVM，内存要算两份**。
要改就跑一次完整构建看机器扛不扛得住，别凭感觉写大数字。

---

## 7. 常见故障对照表

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| `! [rejected] main -> main (non-fast-forward)` | 共享库里已有别人的新提交 | `git pull --rebase share main` 后重新 push |
| `Unable to create '.../.git/index.lock'` | 有另一个 git 进程在操作同一目录 | 确认没在跑 `git status`/构建后，删掉 `index.lock` |
| `detected dubious ownership` | 直接对网络盘上的仓库执行命令（不是通过 clone 到本机） | 只把 `share` 当远程用，别在里面直接 `git` 操作 |
| `Permission denied (publickey)` | 走的是 SSH 地址但没配密钥 | 改用 `S:/git/...` 路径（第 3.3 节） |
| S 盘打叉 / 提示找不到网络路径 | 共享连接断了（服务器重启、睡眠唤醒） | 重新执行 `net use S: ...`，或资源管理器断开重连 |
| 构建日志停在半路、无结论 | 多个 Gradle daemon 抢锁，或内存吃紧被系统杀 | `./gradlew.bat --stop` 清干净后重跑 |
| `Filename too long` | 路径过长 | 项目放浅层目录，别嵌太深 |

---

## 8. 一页速查

```bash
# 开工
git pull share main

# 提交
git add -A
git commit -m "feat: 做了什么（v68）"

# 推送
git push share main

# 被别人抢了先
git pull --rebase share main && git push share main

# 看两边差多少
git rev-list --left-right --count share/main...main   # 左=对方独有 右=你独有

# 构建（只在一台机器上跑）
./gradlew.bat assembleRelease --offline
./gradlew.bat --stop
```

---

**最后一条**：这个文件本身就是规范，**改它就等于改规矩**。改之前先在群里说一声，改完在
`10_文档变更日志.md` 留一行。
