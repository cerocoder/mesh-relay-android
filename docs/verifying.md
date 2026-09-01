# Verifying a change

This project has no local Android SDK and no Gradle wrapper, so **nothing here can be built or run
on the development machine**. Every change is verified in two places, in this order: a CI run, then
the phone. This file is how to reach both, because neither is reachable the obvious way.

## 1. CI, without the `gh` CLI

`gh` is not installed on this machine and there is no `GH_TOKEN`. The repository is public, so the
unauthenticated API is enough for everything below.

Find the run for the commit you just pushed:

```bash
SHA=$(git rev-parse HEAD)
curl -s "https://api.github.com/repos/cerocoder/mesh-relay-android/actions/runs?head_sha=$SHA" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(*[(r['id'],r['status'],r['conclusion']) for r in d['workflow_runs']], sep='\n')"
```

Wait for it (a healthy run is about four minutes; the workflow's own timeout is twenty):

```bash
until S=$(curl -s "https://api.github.com/repos/cerocoder/mesh-relay-android/actions/runs/$RID" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['status'],d['conclusion'])"); \
  echo "$(date +%H:%M:%S) $S"; case "$S" in completed*) true;; *) false;; esac; do sleep 30; done
```

Per-step results, which is how you tell a compile failure from a test failure:

```bash
curl -s ".../actions/runs/$RID/jobs" | python3 -c "…print(j['name'], s['number'], s['name'], s['conclusion'])"
```

**A `cancelled` conclusion is not a failure.** The workflow has
`concurrency: cancel-in-progress: true`, so pushing again kills the previous run. Always poll the
run for the actual `HEAD`, never the newest run on the branch.

**The failure output is a commit comment, not something you need the log API for.** The workflow's
"Post the failure tail as a commit comment" step publishes the tail of `/tmp/gradle.log`:

```bash
curl -s "https://api.github.com/repos/cerocoder/mesh-relay-android/commits/<sha>/comments" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['body'][-7000:])"
```

Read it from the **end**. The body is tens of thousands of characters and begins with the task list
and the whole dependency tree; the Kotlin error is in the last few hundred.

## 2. The APK

GitHub's own artifact endpoint (`/actions/artifacts/{id}/zip`) returns **401** without a token.
`nightly.link` proxies it for public repositories and needs no auth:

```bash
AID=$(curl -s ".../actions/runs/$RID/artifacts" \
  | python3 -c "import sys,json; print([a['id'] for a in json.load(sys.stdin)['artifacts'] if a['name']=='app-debug'][0])")
curl -sL -o app-debug.zip "https://nightly.link/cerocoder/mesh-relay-android/actions/artifacts/$AID.zip"
unzip -o app-debug.zip
```

**Check the package before installing.** There is a sibling project on this machine
(`mesh-test-android`) whose debug artifact is also called `app-debug.apk`, and installing it instead
looks like the change did nothing:

```bash
python3 -c "
import zipfile,re
s=zipfile.ZipFile('app-debug.apk').read('AndroidManifest.xml').decode('utf-16-le','ignore')
print([t for t in re.findall(r'[ -~]{6,}', s) if 'meshrelay' in t or 'meshtest' in t][:3])"
```

It must say `com.cerocoder.meshrelay`. Then `adb install -r app-debug.apk`.

## 3. The phone

An Android device over `adb`, developer options on. The app is a debug build, so `run-as` works.

**Read the layout, do not look at it.** Screenshots hide the defects this project actually has —
F-1, F-3 and F-4 were all found in the view hierarchy and would have been argued about from pixels:

```bash
adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1
adb shell cat /sdcard/w.xml | python3 -c "
import sys,re
s=sys.stdin.read()
for t,b in re.findall(r'text=\"([^\"]*)\"[^>]*bounds=\"([^\"]*)\"', s):
    if t.strip(): print(repr(t), b)"
```

Swap `text=` for `content-desc=` to see the icon buttons, which carry no text. `bounds` is
`[left,top][right,bottom]` in pixels; the reference device is 1080×2340 at density 450, so 1080 px
is 384 dp.

**Scroll inside the list, not the screen.** A swipe that starts outside the `LazyColumn`'s viewport
produces an identical dump and reads as "the list does not scroll". Take the list's bounds from the
dump first and swipe within them.

**Settings can be read and written directly**, which is how a state that is hard to reach through
the UI gets tested:

```bash
adb shell run-as com.cerocoder.meshrelay cat shared_prefs/mesh_relay.xml
adb shell "run-as com.cerocoder.meshrelay sed -i 's|name=\"language\">SYSTEM|name=\"language\">ES|' shared_prefs/mesh_relay.xml"
```

Quote the **whole** `adb shell "run-as … sed …"` as one double-quoted string; nesting the quotes any
other way gives `sed: no pattern`. Force-stop the app before editing and **put the file back the way
you found it** afterwards — this is the owner's own install.

**Check for a crash by process id, not by looking:**

```bash
adb shell pidof com.cerocoder.meshrelay   # empty means it died
adb logcat -d -b crash | tail -20
```

## 4. What "verified" means here

- **Spanish counts.** It is the longer language and half this app's defects only appear in it. A
  claim checked in English only is half-checked, and the plan's global constraints say so.
- **The demo transport is not the radio.** Its local node carries no coordinates, which is exactly
  why F-3 survived 247 tests, 33 reviews and seven green CI runs. A layout claim about a node with a
  position must be read off a real connection.
- CI proves it compiles and the pure logic holds. It has never once seen a screen.
