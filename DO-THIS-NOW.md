# 🎯 5-MINUTE SETUP — Bas Yeh Karo

Maine **sab kuch ready kar diya hai** aapke username (`Suryansh4567`) aur repo name (`KatMovieHD-CS`) ke saath. Files mein koi placeholder nahi hai.

Aapko bas **3 simple cheezein** karni hain:

---

## 🚀 Method 1: GitHub Web UI Se (Easiest — No Terminal!)

### Step 1: Naya repo banao (1 min)
👉 Open karo: **https://github.com/new**

Yeh values daalo:
- **Repository name:** `KatMovieHD-CS`
- **Description:** `CloudStream extension for KatMovieHD`
- **Public** ✅ (Private mat karo!)
- **DON'T** check any of: "Add README", ".gitignore", "Choose a license"

Niche **"Create repository"** dabao.

### Step 2: Saari files upload karo (2 min)
Naya repo create hone ke baad page par dikhayega: **"…or push an existing repository from the command line"**

Iske bajaye scroll up karo aur dhundo: **"uploading an existing file"** link → uspar click karo.

Phir:
1. `cloudstream-katmoviehd/` folder ki **saari files + folders** (with `.github`, `.gitignore`, `gradle/`) ko select karo
2. Browser mein drag karke drop karo
3. Bottom mein commit message: `Initial commit: KatMovieHD extension`
4. **"Commit changes"** dabao

⚠️ **Hidden folders dikhane ke liye** apne file explorer mein:
- Windows: View tab → check "Hidden items"
- Mac: Cmd + Shift + . (period)

### Step 3: Write permission enable karo ⚠️ MOST IMPORTANT (30 sec)
👉 Open karo: **https://github.com/Suryansh4567/KatMovieHD-CS/settings/actions**

Page niche scroll karo → **"Workflow permissions"** section:
- ⚪ Select: **"Read and write permissions"**
- **Save** dabao

### Step 4: Build trigger karo (10 sec)
👉 Open karo: **https://github.com/Suryansh4567/KatMovieHD-CS/actions**

- Left mein **"Build"** workflow click karo
- Right side **"Run workflow"** dropdown → green **"Run workflow"** button dabao
- ⏱️ Wait 5-10 minutes

### Step 5: Verify (30 sec)
Browser mein ye URLs kholo — dono mein content dikhna chahiye (404 nahi):

✅ https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/builds/plugins.json
✅ https://github.com/Suryansh4567/KatMovieHD-CS/tree/builds

### Step 6: CloudStream mein add karo 🎉
App kholo → **Settings → Extensions → Add repository** → paste:

```
https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/repo.json
```

**Add** → **KatMovieHD** install karo → Done! 🎬

---

## 🚀 Method 2: Terminal Se (Fast — 1 Min)

Agar aapke paas terminal aur Git installed hai:

### Step 1: GitHub par repo banao (same as Method 1 Step 1)

### Step 2: Terminal mein:
```bash
cd path/to/cloudstream-katmoviehd
git init -b main
git add .
git commit -m "Initial commit: KatMovieHD extension"
git remote add origin https://github.com/Suryansh4567/KatMovieHD-CS.git
git push -u origin main
```

Ya **even faster** — agar GitHub CLI (`gh`) installed hai:
```bash
cd path/to/cloudstream-katmoviehd
bash SETUP-NOW.sh
```
Yeh repo bhi create kar dega, push bhi karega, permissions bhi enable karega — sab kuch ek command mein!

### Step 3, 4, 5, 6: Same as Method 1

---

## 📋 Quick Reference

| Step | URL |
|------|-----|
| Create repo | https://github.com/new |
| Enable permissions | https://github.com/Suryansh4567/KatMovieHD-CS/settings/actions |
| Trigger build | https://github.com/Suryansh4567/KatMovieHD-CS/actions |
| Verify plugins.json | https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/builds/plugins.json |
| Final install URL (for CloudStream) | `https://raw.githubusercontent.com/Suryansh4567/KatMovieHD-CS/main/repo.json` |

---

## 🐛 Agar Kuch Atak Jaye

| Problem | Solution |
|---------|----------|
| "Build failed" red ❌ | Logs copy karke mujhe bhejo — fix bata dunga |
| "Permission denied" / 403 | Step 3 (write permissions) skip kar diya — wapas enable karo |
| `plugins.json` 404 de raha | Build successful nahi hua — Actions tab check karo |
| CloudStream "repo not found" | URL exactly match karo: `Suryansh4567/KatMovieHD-CS` (capital S, capital K, capital M, dash, capital CS) |
| Movies play nahi ho rahi | Extension log dekho — `KmhdExtractor` ke errors share karo |

---

## ⚡ Total Time Breakdown

- Method 1 (Web UI): **~5 minutes** + 10 min wait for build
- Method 2 (Terminal): **~1 minute** + 10 min wait for build
- Method 2 + GitHub CLI: **~30 seconds** + 10 min wait for build

**Lessgo bhai! 🚀**
