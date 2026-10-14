# Going public and releasing

The steps that only JR can take, in order, with the exact clicks. Everything else (docs, templates, workflows, listings) is already in the repository. Nothing below has been done yet.

## 1. Readiness check (done)

- **Secrets and private details:** every file in the current tree and every file ever committed on any branch (3,195 versions), plus the old `recovery/` archive, were scanned. There are no passwords, keys or tokens, no private server addresses and no third-party mod files (no Photon, Iris, Sodium, Distant Horizons or Voxy jars or packs). The only email addresses in files are public data-provider contacts.
- **Licences:** LGPL-3.0-only (`LICENSE`, `COPYING`, and in the jar). Every data source is credited ([CREDITS.md](../../CREDITS.md), [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md), in game). A full re-scan found no copied code ([LICENSING-AUDIT.md](LICENSING-AUDIT.md)).
- **Workflows:** `release.yml` runs only on pushes to `main`, `combined-*` tags or a manual run, so pull requests from strangers can never publish a release. `ci.yml` builds and tests pull requests with read-only access and no secrets. Third-party actions are pinned to exact commits. Neither workflow uses any secret beyond GitHub's own short-lived token.
- **Community files:** [CONTRIBUTING.md](../../CONTRIBUTING.md), [SECURITY.md](../../SECURITY.md), issue forms and a pull request template under `.github/`.

## 2. Make the repository public (JR)

1. **Update the description.** On https://github.com/pirut/earth-cubic-platform, click the gear next to **About** (right column). Set:
   - Description: `The real Earth in Minecraft at true scale: real heights, real climate and biomes, terrain to the horizon. NeoForge 1.21.1.`
   - Website: the Modrinth page once it exists (leave blank for now).
   - Topics: `minecraft`, `minecraft-mod`, `neoforge`, `earth`, `terrain-generation`, `cubic-chunks`, `gis`.
   - Click **Save changes**.
2. **Turn on private vulnerability reports.** **Settings** → **Code security** (left sidebar; may be called "Advanced Security") → **Private vulnerability reporting** → **Enable**. SECURITY.md points people there.
3. **Check outside pull requests need approval.** **Settings** → **Actions** → **General** → **Approval for running fork pull request workflows from contributors** → keep **Require approval for first-time contributors** (the default) → **Save** if you changed it.
4. **Optional: protect main.** **Settings** → **Rules** → **Rulesets** → **New ruleset** → **New branch ruleset**. Name it `main`, set **Enforcement status** to Active, **Target branches** → **Add target** → **Include default branch**, tick **Restrict deletions**, **Block force pushes** and **Require a pull request before merging**, then **Create**.
5. **Make it public.** **Settings** → **General** → scroll to **Danger Zone** → **Change repository visibility** → **Change to public** → read the warnings → type `pirut/earth-cubic-platform` → **I understand, change repository visibility**.

GitHub keeps every old commit when a repository goes public. Three things in that history become visible, none of them secret. Leaving them is the recommendation, because removing any of them means rewriting the whole history:

- Your Gmail address, on the 26 commits you made yourself. GitHub already shows it on those commits to anyone you share them with. To hide it on future commits: **Settings** (your account, not the repository) → **Emails** → tick **Keep my email addresses private**.
- `Claude-Session:` lines at the end of most commit messages. They link to your private Claude sessions, which nobody else can open.
- The older engine's `recovery/` archive (removed from the tree, still in history). It holds 62 listings of Distant Horizons classes, which the LGPL allows; its notice is `recovery/DISTANT-HORIZONS-NOTICE.md` in the same commits.

## 3. Test the release build (JR)

The newest build is always on the [Releases page](https://github.com/pirut/earth-cubic-platform/releases), marked **Latest**.

1. Make a fresh NeoForge 21.1.259 instance (or 21.1.256 or newer) for Minecraft 1.21.1 with at least 6 GB of memory.
2. Put only the release jar in `mods`, start the game and create a **new** Earth world.
3. Check: the spawn picker, the travel map (**N** in creative), `/earth visit everest`, a little survival, a Nether portal.
4. Add Sodium, Iris and stock Photon v1.3b; check again in the same world.
5. Optional: a dedicated server with a friend.

## 4. The public version

### Versioning

- **Scheme:** `0.1.0-alpha.N`, where **N is the build number**, so build 38 is `0.1.0-alpha.38`. Players and testers then use one number, and the GitHub release reads "Earth Platform 0.1.0-alpha.38 (build 38)".
- **Channel:** Alpha on Modrinth and CurseForge until the known issues in [CHANGELOG.md](../../CHANGELOG.md) are fixed; then `0.1.0-beta.N`, and `0.1.0` for the first stable release.
- **Later:** `0.2.0` for the next large feature set; a new Minecraft version keeps the mod version and adds the game version on the listing.

### How it is set

JR chose this scheme on 2026-10-14. `mod_version` in `minecraft/gradle.properties` is `0.1.0-alpha.38`, for build 38.

- **Each new build** raises `mod_version` to that build's number in the same change that adds `docs/release/notes/combined-NN.md`. The release workflow stops with an error if the two don't match, so a release is never labelled with the wrong version.
- The jar, the GitHub release title ("Earth Platform 0.1.0-alpha.38 (build 38)") and the Mods screen all take the version from that one line.

## 5. Publish on Modrinth (JR)

Paste the fields from [STORE-PAGE.md](STORE-PAGE.md).

1. Go to https://modrinth.com and **Sign in** (GitHub works).
2. Click **+** (top bar) → **Create a project**. Name `Earth Platform`, URL `earth-platform`, Visibility **Public**, Summary from the store page → **Create project**.
3. In the project's **Settings**: **General** → upload `docs/images/icon.png` as the icon. **Tags** → Worldgen (primary), Adventure, Transportation. **License** → `LGPL-3.0-only`. **Links** → Source, Issues and Wiki. **Description** → paste the description. **Environment** (if shown) → client required, server required.
4. **Gallery** → **Upload an image** for each picture in the store-page order, with its title and caption; mark the Grand Canyon one **Featured**.
5. **Versions** → **Create a version** → drag in the jar from the GitHub release. Version number `0.1.0-alpha.N`, name `Earth Platform 0.1.0-alpha.N`, channel **Alpha**, loaders **NeoForge**, game versions **1.21.1**. **Add dependency** → Sodium (Optional), Iris (Optional). Paste the build's notes as the changelog → **Create**.
6. Back on the project page, click **Submit for review**. Modrinth's moderators check it, usually within a few days.

## 6. Publish on CurseForge (JR)

1. Go to https://authors.curseforge.com and sign in.
2. **Create a project** → Game **Minecraft**, Class **Mods** → name `Earth Platform`, summary, description (switch the editor to **Markdown** and paste), main category **World Gen**, extra categories **Biomes** and **Adventure and RPG**, licence **GNU Lesser General Public License version 3 (LGPLv3)**, logo `docs/images/icon.png` → **Create project**.
3. In the project, add the **Source** and **Issues** links, and upload the gallery images.
4. **Files** → **Upload file** → the jar. Display name `Earth Platform 0.1.0-alpha.N`, release type **Alpha**, game versions **1.21.1**, **NeoForge**, **Java 21**, **Client** and **Server**. **Relations** → Sodium and Iris as **Optional Dependency**. Paste the changelog → **Upload**.
5. CurseForge reviews new projects and files before they go live.

## 7. After publishing

- Put the Modrinth link in the repository's **About** → Website.
- Link the Modrinth and CurseForge pages from the top of the README.
- For each later build: the GitHub release appears by itself when the build merges into `main`; upload the same jar as a new version on both sites with that build's notes.
