# BCU Attack LD Editor

Edit attack ranges and drag their Near/Far edges directly in BCU's Animation Editor.

## What's new in 1.0.2

Fixed: on some BCU 0-5-8-8 jars, the LD controls never appeared in the Animation Editor. Those jars are a slightly different build of the same version, and the safety check turned the feature off. They are now supported when all the BCU code the feature needs is present.

If you installed 1.0.1 and could not find the LD controls, run the new installer again. See the [changelog](CHANGELOG.md) for details.

## Install

1. Close BCU.
2. Copy `Install-BCU-Attack-LD-Editor.jar` into your BCU folder, next to the original BCU `.jar` file.
3. Open the installer.
4. Start BCU with `BCU New Features.bat`, or use `BCU New Features (No Console).vbs` to hide the console window.

The installer leaves the original BCU file unchanged. Other compatible patches can use the same launcher.

Tested on BCU **50302** and **50808 (0-5-8-8)**. Other versions are not guaranteed to work. See [Compatibility](COMPATIBILITY.md).

## Use

1. Open Animation Editor and select `attack`, or an extra attack from `attack2` to `attack5`.
2. Select the unit and hit in the bar below the preview.
3. Enter `LD Near` and `LD Far`, or turn on **Hitboxes** and drag the small squares at the range edges.
4. Click **Save all** to save your changes.

The bar appears only when the animation belongs to a custom unit in a loaded, editable pack. The unit must have the selected attack form.

- **Enemy** changes the preview direction: checked faces right; unchecked faces left. Distances are measured from the unit's root. Negative values extend in the opposite direction.
- `0 / 0` turns LD off and hides its preview. Otherwise, Far must be greater than Near; the other value adjusts when needed.
- Animation **Undo** does not undo LD changes.

## Remove

Open a terminal in the BCU folder and run:

```bat
java -jar Install-BCU-Attack-LD-Editor.jar --uninstall
```

This removes the feature from the launcher. Your packs and the feature folder are kept. The shared launcher stays if other features still use it.

## Package files

Only the installer is needed to install the feature. For source files and build details, see [Source files](SOURCE-MANIFEST.md). Patch authors can use the [shared launcher guide](FEATURE-LAUNCHER.md).
