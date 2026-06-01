#!/bin/bash
# ============================================================================
# One-click setup: pushes this folder to GitHub as a new public repo.
# Usage:
#   1. Download/unzip this folder to your computer
#   2. Open terminal in this folder
#   3. Run: bash SETUP-NOW.sh
#
# Requirements:
#   - git installed
#   - GitHub CLI (gh) installed and authenticated (`gh auth login`)
#   OR
#   - You can do it manually — script will print instructions
# ============================================================================
set -e

USERNAME="Suryansh4567"
REPO_NAME="KatMovieHD-CS"
REPO_URL="https://github.com/${USERNAME}/${REPO_NAME}"

echo "════════════════════════════════════════════════════════════════"
echo "  CloudStream KatMovieHD Extension - One-Click Setup"
echo "════════════════════════════════════════════════════════════════"
echo "  Username:  ${USERNAME}"
echo "  Repo:      ${REPO_NAME}"
echo "  URL:       ${REPO_URL}"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Check git
if ! command -v git &> /dev/null; then
    echo "❌ git is not installed. Install it first:"
    echo "   Windows: https://git-scm.com/download/win"
    echo "   Mac:     brew install git"
    echo "   Linux:   sudo apt install git"
    exit 1
fi

# Initialize repo
echo "📦 Initializing git repository..."
if [ ! -d .git ]; then
    git init -b main
    git config user.email "${USERNAME}@users.noreply.github.com"
    git config user.name "${USERNAME}"
fi

echo "📝 Staging files..."
git add .
git commit -m "Initial commit: KatMovieHD CloudStream extension" 2>/dev/null || echo "   (nothing new to commit)"

# Try to create repo using GitHub CLI if available
if command -v gh &> /dev/null; then
    echo ""
    echo "✨ GitHub CLI detected — creating repo automatically..."
    if gh repo view "${USERNAME}/${REPO_NAME}" &>/dev/null; then
        echo "   Repo already exists, pushing to it..."
        git remote remove origin 2>/dev/null || true
        git remote add origin "${REPO_URL}.git"
    else
        gh repo create "${USERNAME}/${REPO_NAME}" --public --source=. --remote=origin --description "CloudStream extension for KatMovieHD" --push
        echo ""
        echo "✅ Repo created and pushed!"
    fi

    if ! git ls-remote origin &>/dev/null; then
        git push -u origin main
    fi

    echo ""
    echo "🔐 Enabling 'Read and write permissions' on Actions..."
    gh api -X PUT "repos/${USERNAME}/${REPO_NAME}/actions/permissions/workflow" \
        -f default_workflow_permissions='write' \
        -F can_approve_pull_request_reviews=true 2>/dev/null && \
        echo "   ✅ Permissions set automatically" || \
        echo "   ⚠️  Could not set permissions via API — do it manually (Step 3 below)"

    echo ""
    echo "🚀 Triggering first build..."
    gh workflow run build.yml 2>/dev/null && \
        echo "   ✅ Build triggered" || \
        echo "   ⚠️  Trigger from web UI"

    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "  🎉 ALL DONE!"
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    echo "Wait 5-10 minutes for the build, then verify:"
    echo "   https://github.com/${USERNAME}/${REPO_NAME}/actions"
    echo ""
    echo "Add this URL in CloudStream:"
    echo "   https://raw.githubusercontent.com/${USERNAME}/${REPO_NAME}/main/repo.json"
    echo ""
    exit 0
fi

# Fallback: manual instructions if `gh` is not installed
echo ""
echo "════════════════════════════════════════════════════════════════"
echo "  GitHub CLI (gh) not installed — do these 3 manual steps:"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "1️⃣  CREATE REPO ON GITHUB"
echo "   Open: https://github.com/new"
echo "   - Repository name: ${REPO_NAME}"
echo "   - Public ✅"
echo "   - DON'T add README/.gitignore"
echo "   - Click 'Create repository'"
echo ""
echo "2️⃣  PUSH THIS FOLDER"
echo "   git remote add origin ${REPO_URL}.git"
echo "   git push -u origin main"
echo ""
echo "3️⃣  ENABLE WRITE PERMISSIONS  ⚠️ CRITICAL!"
echo "   Open: ${REPO_URL}/settings/actions"
echo "   Scroll to 'Workflow permissions'"
echo "   Select 'Read and write permissions' → Save"
echo ""
echo "4️⃣  TRIGGER BUILD"
echo "   Open: ${REPO_URL}/actions"
echo "   Click 'Build' workflow → 'Run workflow'"
echo "   Wait 5-10 minutes"
echo ""
echo "5️⃣  ADD TO CLOUDSTREAM"
echo "   Settings → Extensions → Add repository"
echo "   URL: https://raw.githubusercontent.com/${USERNAME}/${REPO_NAME}/main/repo.json"
echo ""
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "💡 TIP: Install GitHub CLI to skip steps 1-4 next time:"
echo "   Windows: winget install GitHub.cli"
echo "   Mac:     brew install gh"
echo "   Linux:   https://github.com/cli/cli#installation"
echo "   Then:    gh auth login"
echo ""
