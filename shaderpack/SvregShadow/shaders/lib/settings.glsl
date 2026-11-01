// ---- SvregShadow options (Iris: Video Settings > Shader Packs > Shader Pack Settings)
#define SHADOW_DARKNESS 0.45 // [0.20 0.30 0.40 0.45 0.50 0.60 0.70 0.80]
#define SHADOW_TINT 1 // [0 1 2]
#define SOFT_SHADOWS 1 // [0 1]
#define MOON_SHADOWS 1 // [0 1]
#define ENTITY_SHADOWS // Mob & player shadows
#ifdef ENTITY_SHADOWS
#endif

const int shadowMapResolution = 1024; // [512 1024 1536 2048]
const float shadowDistance = 64.0; // [32.0 48.0 64.0 96.0 128.0]
const float sunPathRotation = -20.0; // [-40.0 -30.0 -20.0 -10.0 0.0 10.0 20.0 30.0 40.0]
const bool shadowHardwareFiltering = true;
const float shadowDistanceRenderMul = 1.0;
