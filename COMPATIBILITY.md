# Compatibility

Tested BCU versions:

- 50302
- 50808 (0-5-8-8)

Automated tests cover both preview renderers, Near/Far dragging, and Direct Edit integration. These are not a substitute for testing in the BCU window.

The feature checks BCU at startup and stays off if the version or required code does not match. The original BCU file is not changed.

Other builds of a tested version (for example a re-released 0-5-8-8 jar) are accepted when every required class, method and field matches. The log then shows `build=unverified-same-version`. Each patch still checks its own anchor points and turns the whole feature off if one has changed.

The installer can find newer BCU files, but this does not mean the feature supports every version. New versions need a compatibility update and testing.
