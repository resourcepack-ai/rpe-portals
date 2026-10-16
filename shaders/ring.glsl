// The portal's ring: a spinning ring of molten sparks flinging streaks off its
// edge, with a clear hole in the middle. The far side is not drawn here: the
// Portals plugin draws it in the hole with the game's text shader, from maps
// it hangs at the ring. OPENING spins the ring out of a small glowing ball,
// CLOSING pinches it back into one, and CLOSED is that ball.
//
// It is a 3D shader object as RP Engine draws them: object() is called for
// each pixel of the object's bounding sphere, and the rpw_* values and helpers
// (state, time in the state, noise, hash) come from the object framework it is
// linked into.
const int STATE_CLOSED = 0;
const int STATE_OPENING = 1;
const int STATE_OPEN = 2;
const int STATE_CLOSING = 3;

const float RING = 0.8;
const float GROUND = -0.8;

float skOpen;
float skPower;
float skTime;

// Closed is not gone: a seed of the portal stays where it was, a small solid
// ember that flickers and spits sparks, so it can be found and opened again.
// The ring opens out of it and pinches back into it.
const float DUST = 0.06;
const float DUST_POWER = 0.6;

void spState() {
  float st = rpw_state_time;
  skOpen = DUST;
  skPower = DUST_POWER;
  if (rpw_state == STATE_OPENING) {
    skOpen = mix(DUST, 1.0, smoothstep(0.15, 1.4, st));
    skPower = mix(DUST_POWER, 1.0, smoothstep(0.0, 0.3, st));
  } else if (rpw_state == STATE_OPEN) {
    skOpen = 1.0;
    skPower = 1.0;
  } else if (rpw_state == STATE_CLOSING) {
    skOpen = mix(1.0, DUST, smoothstep(0.0, 1.0, st));
    skPower = mix(1.0, DUST_POWER, smoothstep(0.5, 1.1, st));
  }
}

vec3 sparks(vec2 q, float time) {
  float r0 = RING * skOpen;
  float r = length(q);
  float a = atan(q.y, q.x);
  float d = r - r0;
  float bottom = 1.0 + 1.0 * smoothstep(-0.2, -1.0, q.y / max(r, 0.001));
  // nothing reaches the ground the ring stands over (-0.89 here)
  float above = smoothstep(-0.9, -0.83, q.y);
  float flick = 0.55 + 0.45 * rpw_noise(vec3(a * 14.0 + time * 25.0, r * 40.0, time * 4.0));
  vec3 col = vec3(1.0, 0.5, 0.12) * exp(-abs(d) * 18.0) * 0.3 * mix(1.0, above, step(0.0, d));
  col += vec3(1.0, 0.55, 0.15) * exp(-abs(d) * 45.0) * flick * 3.0;
  col += vec3(1.0, 0.92, 0.7) * exp(-abs(d) * 150.0) * 2.5;
  for (int L = 0; L < 4; L++) {
    float fl = float(L);
    float n = 70.0 + 45.0 * fl;
    float u = (a - d * (3.0 + 1.5 * fl)) * n / 6.2831853 - time * (9.0 + 4.0 * fl);
    float id = floor(u);
    float h = rpw_hash(vec3(id, fl, 2.0));
    // Shorter streaks off a smaller ring: the closed spark sheds dust, not flares.
    float len = (0.04 + 0.28 * h * h) * (1.0 + 0.4 * (bottom - 1.0)) * (0.25 + 0.75 * skOpen);
    float along = d / len;
    float w = abs(fract(u) - 0.5) * (r + 0.05) * 6.0;
    float line = smoothstep(0.09, 0.0, w) * step(0.0, d) * (1.0 - smoothstep(0.0, 1.0, along));
    line *= step(0.4 - 0.2 * (bottom - 1.0), h) * above;
    col += mix(vec3(1.0, 0.85, 0.5), vec3(1.0, 0.4, 0.08), along) * line * (1.6 - along);
    // a few drifting inward, the way the real thing sheds into the hole
    float inner = smoothstep(0.05, 0.0, w) * step(d, 0.0) * smoothstep(-0.08, 0.0, d) * step(0.8, h);
    col += vec3(1.0, 0.6, 0.2) * inner;
  }
  return col * skPower * step(0.001, r0 + 0.02);
}

// How much of the ember shows: all of it shut, none once the ring has opened
// a little way out of it.
float skSeed() {
  return 1.0 - smoothstep(DUST, 0.35, skOpen);
}

// The shut portal, as a ball: a white-hot core in a softly breathing orange
// glow, its surface stirring, and now and then a spark drifting off it in any
// direction, arcing down. Drawn in 3D, so it is round from every side. The
// colour comes back with how far along the ray it was found (w), or w < 0.
vec4 seed(vec3 ro, vec3 rdIn, float time) {
  // The ray may not be of unit length in the object's frame: a flat ring does
  // not mind, a ball comes out egg-shaped. Distances along it are scaled back.
  float rdLen = max(length(rdIn), 1e-6);
  vec3 rd = rdIn / rdLen;
  float tc = -dot(ro, rd);
  vec3 pc = ro + rd * tc;
  float d = length(pc);
  float crackle = 0.88 + 0.12 * rpw_noise(vec3(time * 3.0, 3.0, 1.0));
  float rs = 0.055 * (0.92 + 0.08 * crackle);
  vec3 col = vec3(0.0);
  float at = -1.0;
  // The glow round it, by how near the ray passes the middle.
  float halo = exp(-d * 34.0) * 0.9 * crackle;
  col += vec3(1.0, 0.45, 0.1) * halo;
  col += vec3(1.0, 0.62, 0.2) * smoothstep(rs * 1.6, rs * 0.8, d) * 1.4 * crackle;
  if (halo > 0.01) at = tc;
  // The ball itself, its surface boiling slowly.
  if (d < rs * 1.15) {
    vec3 n0 = normalize(pc + rd * 1e-4);
    float boil = 1.0 + 0.16 * (rpw_noise(n0 * 3.0 + vec3(time * 1.5, time * 1.2, 7.0)) - 0.5);
    float r = rs * boil;
    if (d < r) {
      float th = sqrt(r * r - d * d);
      vec3 hp = ro + rd * (tc - th);
      vec3 n = normalize(hp);
      float facing = clamp(dot(n, -rd), 0.0, 1.0);
      float stir = 0.85 + 0.15 * rpw_noise(n * 6.0 + vec3(0.0, time * 1.4, 0.0));
      // White in the middle of the disc it shows, orange at its rim.
      col += mix(vec3(1.0, 0.55, 0.15), vec3(1.0, 0.95, 0.8), smoothstep(0.1, 0.8, facing)) * 3.0 * stir;
      at = tc - th;
    }
  }
  // Sparks: each of a few lights now and then, flies out in its own
  // direction and arcs down, fading as it goes.
  for (int k = 0; k < 7; k++) {
    float fk = float(k);
    float tick = time * 1.6 + rpw_hash(vec3(fk, 5.0, 1.0)) * 7.0;
    float h = rpw_hash(vec3(fk, 3.0, floor(tick)));
    if (h < 0.7) continue;
    float life = fract(tick);
    vec3 dir = normalize(vec3(rpw_hash(vec3(fk, floor(tick), 11.0)), rpw_hash(vec3(fk, floor(tick), 12.0)) * 0.8 + 0.1,
        rpw_hash(vec3(fk, floor(tick), 13.0))) * 2.0 - 1.0);
    float reach = 0.06 + 0.14 * h;
    float lh = life;
    float lt = max(life - 0.35, 0.0);
    vec3 head = dir * (rs + reach * lh) + vec3(0.0, -0.05 * lh * lh, 0.0);
    vec3 tail = dir * (rs + reach * lt) + vec3(0.0, -0.05 * lt * lt, 0.0);
    // How near the ray passes the streak from tail to head.
    vec3 ab = head - tail;
    vec3 w0 = ro - tail;
    float a = dot(rd, rd);
    float b = dot(rd, ab);
    float c = dot(ab, ab);
    float dd = dot(rd, w0);
    float e = dot(ab, w0);
    float den = a * c - b * b;
    float sk = den > 1e-8 ? clamp((a * e - b * dd) / den, 0.0, 1.0) : 0.0;
    float tr = max(dot(tail + ab * sk - ro, rd), 0.0);
    float dl = length(ro + rd * tr - (tail + ab * sk));
    float line = smoothstep(0.006, 0.0, dl) * (1.0 - life);
    if (line > 0.001) {
      col += mix(vec3(1.0, 0.4, 0.08), vec3(1.0, 0.9, 0.6), sk) * line * 1.6;
      if (at < 0.0) at = tr;
    }
  }
  return vec4(col, at < 0.0 ? at : at / rdLen);
}

vec4 object(vec3 ro, vec3 rd, float t0, float t1, float time, vec3 light, inout float depth) {
  spState();
  if (skOpen <= 0.0 && skPower <= 0.0) return vec4(0.0);
  float sd = skSeed();
  // A small ring is no look of its own: its sparks fade as it shrinks and the
  // ember takes its place, so shutting goes straight from ring to ember.
  float ring = smoothstep(0.15, 0.4, skOpen);
  vec3 col = vec3(0.0);
  float at = -1.0;
  float tp = abs(rd.z) > 1e-4 ? -ro.z / rd.z : -1.0;
  if (ring > 0.0 && tp >= t0 && tp <= t1) {
    vec3 h = ro + rd * tp;
    float r = length(h.xy);
    col += sparks(h.xy, time) * ring;
    // a faint heat shimmer just inside the ring, the edge of the window
    float r0 = RING * skOpen;
    float lip = smoothstep(r0 - 0.12, r0, r) * step(r, r0) * skPower * (1.0 - sd);
    col += vec3(1.0, 0.45, 0.1) * lip * 0.25 * (0.7 + 0.3 * rpw_noise(vec3(atan(h.y, h.x) * 9.0, r * 30.0, time * 3.0)));
    at = tp;
  }
  if (sd > 0.0) {
    vec4 ball = seed(ro, rd, time);
    if (ball.w >= 0.0) {
      col += ball.rgb * sd;
      at = at < 0.0 ? ball.w : min(at, ball.w);
    }
  }
  if (at < 0.0) return vec4(0.0);
  float glow = clamp(max(col.r, max(col.g, col.b)), 0.0, 1.0);
  // Every pixel drawn here is written to the depth buffer, so a glow too faint
  // to see still hides the clouds behind it: a disc cut out of the sky round
  // the ring. The faintest glow is faded to nothing instead.
  float alpha = glow * smoothstep(0.05, 0.2, glow);
  if (alpha < 0.004) return vec4(0.0);
  depth = clamp(at, t0, t1);
  return vec4(col / max(glow, 0.001), alpha);
}
