# PSCHAIR Patches — Lawnchair 16

## Overzicht
Dit bestand documenteert alle PSCHAIR-aanpassingen die bij elke upstream-release opnieuw
moeten worden toegepast. Op dit moment bevat de fork één samenhangende feature:
**Private Space op de Homescreen**.

- **Toegepast op release:** `16-dev` (huidige fork-basis)
- **Markering in upstream-bestanden:** `// PSCHAIR-PATCH BEGIN` … `// PSCHAIR-PATCH END`

---

## Feature: Private Space op de Homescreen

### Doel
Deze feature maakt apps uit de Android **Private Space** sleepbaar naar de homescreen
(workspace) — maar **uitsluitend** wanneer de Private Space ontgrendeld is. Een tik op een
vergrendelde Private Space-app die op de homescreen staat, toont **eenmalig** de
Private Space-unlock (de echte profielvergrendeling van Android) en start de app pas na
succesvolle ontgrendeling. De single-prompt-garantie werkt via een `unlockInProgress`-gate
plus een autoritatieve quiet-mode-check (`UserManager.isQuietModeEnabled`), zodat de
her-binnenkomst na ontgrendelen niet opnieuw om de pincode vraagt. Het gedrag is in/uit te
schakelen via een Lawnchair-voorkeur en beweegt live mee met lock/unlock-wisselingen zonder
herstart.

De beslissingslogica is **gecentraliseerd** in `PrivateSpaceHomeHelper.kt` (PSCHAIR-laag);
de upstream-bestanden bevatten alleen kleine, gemarkeerde aanroepen naar deze helper.

---

### Gewijzigde UPSTREAM Launcher3-bestanden (met `// PSCHAIR-PATCH` markeringen)

Deze bestanden horen bij upstream Launcher3 en moeten bij elke release-merge opnieuw worden
nagekeken. De wijzigingen zijn bewust minimaal: bij voorkeur één aanroep naar
`PrivateSpaceHomeHelper`.

| # | Bestand | Doel | Aard van de wijziging |
|---|---------|------|------------------------|
| 1 | `src/com/android/launcher3/dragndrop/DragController.java` | Poortwachter voor slepen | `isItemPinnable()` raadpleegt de live `PrivateSpaceHomeHelper.canPinPrivateItem(...)`, zodat Private Space-items sleepbaar zijn wanneer ontgrendeld. Niet-private gedrag blijft identiek. |
| 2 | `src/com/android/launcher3/popup/PopupContainerWithArrow.java` | Long-press/popup drag-handler | De drag-handler wordt ook aangemaakt voor ontgrendelde Private Space-items (extra `pschairAllowPrivatePin`-conditie). |
| 3 | `src/com/android/launcher3/accessibility/LauncherAccessibilityDelegate.java` | Accessibility-actie | `supportAddToWorkSpace()` biedt "toevoegen aan startscherm" aan voor ontgrendelde Private Space-items via dezelfde live-check. |
| 4 | `src/com/android/launcher3/model/data/AppInfo.java` | Statische pin-vlag conditioneel | `FLAG_NOT_PINNABLE` wordt voor private items alleen gezet wanneer de feature uit staat; gebruikt `apiWrapper.getContext()` om de toggle te lezen. |
| 5 | `src/com/android/launcher3/model/data/WorkspaceItemInfo.java` | Idem voor deep-shortcuts | Zelfde conditionele `FLAG_NOT_PINNABLE`-logica; leest de toggle via de constructor-context. |
| 6 | `quickstep/src/com/android/launcher3/model/data/TaskViewItemInfo.kt` | Idem voor taakitems (recents) | Zelfde conditionele `FLAG_NOT_PINNABLE`-logica voor recents/taakitems. |
| 7 | `src/com/android/launcher3/touch/ItemClickHandler.java` | Klik op vergrendelde private app | In `startAppShortcutOrInfoActivity`: als het item een vergrendelde private app is (`isPrivateProfileLocked`), wordt eenmalig `requestUnlockThenRun(...)` aangeroepen; na ontgrendelen komt de methode opnieuw langs en start de app direct. |
| 8 | `src/com/android/launcher3/util/ApiWrapper.java` | Context-getter | Kleine gemarkeerde getter `getContext()` toegevoegd, zodat `AppInfo` de feature-toggle kan lezen. |

---

### PSCHAIR-laag (`app.lawnchair` / resources, GEEN markering nodig)

Deze bestanden bestaan niet in upstream en hoeven daarom niet gemarkeerd te worden; ze
worden bij een merge gewoon meegenomen.

| Bestand | Status | Doel |
|---------|--------|------|
| `lawnchair/src/app/lawnchair/privatespace/PrivateSpaceHomeHelper.kt` | NIEUW | Centrale beslislogica: pinbaarheid (`canPinPrivateItem`) op basis van live unlock-status, plus single-prompt unlock-op-tik (`isPrivateProfileLocked` + `requestUnlockThenRun`) met `unlockInProgress`-gate en CME-veilige listener-afhandeling. |
| `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | Gewijzigd | Preference `allowPrivateSpaceOnHome` (default `true`), volgens het patroon van `lockHomeScreen`. |
| `lawnchair/res/values/config.xml` | Gewijzigd | Bool `config_default_allow_private_space_on_home` = `true` (configureerbare standaardwaarde). |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Gewijzigd | `SwitchPreference` voor de toggle in de Homescreen-instellingen. |
| `lawnchair/res/values/strings.xml` | Gewijzigd | Strings `private_space_on_home_label` en `private_space_on_home_description`. |

---

### Herapplicatie-tip voor toekomstige releases

Bij het synchroniseren met een nieuwe Lawnchair/Launcher3-release:

1. Zoek in de codebase op `PSCHAIR-PATCH` om **alle** upstream-aanrakingen terug te vinden.
   In `16-dev` zijn dit exact **8 bestanden** (zie tabel hierboven).
2. Pas elke gemarkeerde wijziging opnieuw toe; de blokken zijn klein en bevatten meestal
   alleen een aanroep naar `PrivateSpaceHomeHelper`.
3. De volledige beslislogica zit gecentraliseerd in
   `lawnchair/src/app/lawnchair/privatespace/PrivateSpaceHomeHelper.kt`. Als de helper-API
   ongewijzigd blijft, beperkt het merge-werk zich tot het terugplaatsen van de 8 markeringen.
4. **Raak geen gegenereerde bestanden aan** (`flags/.../FeatureFlagsImpl.java`,
   `flags/.../Flags.java`, `flags/.../CustomFeatureFlags.java`, `aconfig/*`). De feature
   gebruikt een Lawnchair-preference, geen aconfig-flag.

---

---

## Rebranding: PSChair (naam + icoon)

### Doel
De fork presenteert zich als **PSChair** (PrivateSpaceChair) met een eigen app-icoon
in plaats van de originele Lawnchair-branding.

### App-naam
| Bestand | Wijziging |
|---------|-----------|
| `build.gradle` (root) | `resValue("string", "derived_app_name", ...)`: release `Lawnchair` → `PSChair`, debug `Lawnchair (Debug)` → `PSChair (Debug)`. Dit is het bedoelde override-punt; de basis `app_name` en vertalingen blijven ongemoeid. |

> De `applicationId` (`app.lawnchair` / `.debug` / `.nightly` / `.play`) is **bewust ongewijzigd**
> gelaten: aanpassen breekt Obtainium-tracking, signing en vereist een data-migratie.

### App-icoon
Bronafbeeldingen: `icon/pschair.png` (vierkant, full-bleed) en `icon/pschairround.png`
(rond), beide 1254×1254 en volledig ondoorzichtig.

| Resource | Wijziging |
|----------|-----------|
| `res/mipmap-*/ic_launcher_home_background.png` (108→432) | Vervangen door `pschair.png` (full-bleed); de systeemmask vormt het icoon per toestel. |
| `res/mipmap-*/ic_launcher_home_foreground.png` (108→432) | Vervangen door een volledig transparante laag, zodat de full-bleed background zuiver doorkomt. |
| `res/mipmap-*/ic_launcher_home.png` (48→192) | Legacy square (Android < 8) ← `pschair.png`. |
| `res/mipmap-*/ic_launcher_home_round.png` (48→192) | Legacy round (Android < 8) ← `pschairround.png`. |
| `ic_launcher_home-playstore.png` (512) | Play Store-listingafbeelding ← `pschair.png`. |

De adaptive-icon XML's (`res/drawable/ic_launcher_home.xml`, `res/mipmap-anydpi-v26/ic_launcher_home*.xml`)
bleven ongewijzigd — alleen de bitmap-lagen waarnaar ze verwijzen zijn vervangen.

**Bekende beperking:** de monochrome themed-icon (`lawnchair/res/drawable/ic_launcher_home_monochrome.xml`)
toont nog het originele logo-silhouet. Een vector-monochrome kan niet betrouwbaar uit een
raster-PNG worden gegenereerd; dit kan later handmatig vervangen worden.

### Herapplicatie bij toekomstige releases
- De naam-override staat in `build.gradle` (twee `derived_app_name`-regels) — bij merge controleren.
- De icoon-bitmaps in `res/mipmap-*` worden bij upstream-updates zelden gewijzigd, maar als upstream
  het icoon vernieuwt, opnieuw overschrijven met de PSChair-art (zie `icon/`-bronnen).

---

## Verwijderde patches
(Geen — er zijn nog geen patches upstream opgenomen.)
