#version 330

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D TranslucentSampler;
uniform sampler2D TranslucentDepthSampler;
uniform sampler2D ItemEntitySampler;
uniform sampler2D ItemEntityDepthSampler;
uniform sampler2D ParticlesSampler;
uniform sampler2D ParticlesDepthSampler;
uniform sampler2D WeatherSampler;
uniform sampler2D WeatherDepthSampler;
uniform sampler2D CloudsSampler;
uniform sampler2D CloudsDepthSampler;

in vec2 texCoord;

vec4 color_layers[6] = vec4[](vec4(0.0), vec4(0.0), vec4(0.0), vec4(0.0), vec4(0.0), vec4(0.0));
float depth_layers[6] = float[](0, 0, 0, 0, 0, 0);
int active_layers = 0;

out vec4 fragColor;

void try_insert(vec4 color, float depth) {
    if (color.a == 0.0) {
        return;
    }

    color_layers[active_layers] = color;
    depth_layers[active_layers] = depth;

    int jj = active_layers++;
    int ii = jj - 1;
    while (jj > 0 && depth_layers[jj] < depth_layers[ii]) {
        float depthTemp = depth_layers[ii];
        depth_layers[ii] = depth_layers[jj];
        depth_layers[jj] = depthTemp;

        vec4 colorTemp = color_layers[ii];
        color_layers[ii] = color_layers[jj];
        color_layers[jj] = colorTemp;

        jj = ii--;
    }
}

vec3 blend(vec3 dst, vec4 src) {
    return (dst * (1.0 - src.a)) + src.rgb;
}

void rpe_vanilla_main() {
    color_layers[0] = vec4(texture(MainSampler, texCoord).rgb, 1.0);
    depth_layers[0] = texture(MainDepthSampler, texCoord).r;
    active_layers = 1;

    try_insert(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);
    try_insert(texture(ItemEntitySampler, texCoord), texture(ItemEntityDepthSampler, texCoord).r);
    try_insert(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);
    try_insert(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);
    try_insert(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);

    vec3 texelAccum = color_layers[0].rgb;
    for (int ii = 1; ii < active_layers; ++ii) {
        texelAccum = blend(texelAccum, color_layers[ii]);
    }

    fragColor = vec4(texelAccum.rgb, 1.0);
}


const float RPW_NEAR = 0.05;
const float RPW_TAN = 0.70021;

// Whether depth runs backwards (1 near, 0 far), which 26.2 does. Worked out
// per frame from the one pixel where the answer is known: the object passed
// the depth test, so it is nearer than the scene behind it.
bool rpw_reversed = false;

// The far plane is max(render distance x 4, cloud range x 16) blocks, and the
// cloud range defaults to 128 chunks, so it is 2048 for nearly everybody.
// Treating it as infinite is a percent out at ten blocks: nothing for a
// contact line, but it bends the surface the lens solves its sphere from.
const float RPW_FAR = 2048.0;

float rpw_linear(float d) {
    return rpw_reversed
        ? RPW_NEAR * RPW_FAR / max(d * (RPW_FAR - RPW_NEAR) + RPW_NEAR, 1e-7)
        : RPW_NEAR * RPW_FAR / max(RPW_FAR - d * (RPW_FAR - RPW_NEAR), 1e-6);
}

// 0 is not ours, 1 an object, 2 a lens, 3 a gravity lens.
float rpw_flag(vec4 c) {
    if (c.a <= 0.0) return 0.0;
    float m = mod(floor(c.a * 255.0 + 0.5), 8.0);
    return m == 5.0 ? 2.0 : (m == 3.0 ? 1.0 : (m == 7.0 ? 3.0 : 0.0));
}


vec3 rpw_view(vec2 uv, float z, vec2 tanxy) {
    return vec3((uv * 2.0 - 1.0) * tanxy * z, -z);
}

vec2 rpw_project(vec3 dir, vec2 tanxy) {
    return (dir.xy / max(-dir.z, 1e-4)) / tanxy * 0.5 + 0.5;
}

vec3 rpw_surface(vec2 uv, vec2 tanxy, vec3 fallback) {
    if (rpw_flag(texture(ItemEntitySampler, uv)) == 0.0) return fallback;
    return rpw_view(uv, rpw_linear(texture(ItemEntityDepthSampler, uv).r), tanxy);
}

// Where a ray from the eye along unit dir enters and leaves a sphere.
vec2 rpw_sphere_hits(vec3 dir, vec3 C, float R) {
    float b = dot(dir, C);
    float h = sqrt(max(b * b - (dot(C, C) - R * R), 0.0));
    return vec2(b - h, b + h);
}

// How far behind the sphere's near and far walls the scene is, in depth,
// along the ray through uv.
vec2 rpw_gaps(vec2 uv, vec2 tanxy, vec3 C, float R) {
    vec3 dir = normalize(rpw_view(uv, 1.0, tanxy));
    vec2 t = rpw_sphere_hits(dir, C, R);
    float zs = rpw_linear(texture(MainDepthSampler, uv).r);
    return vec2(zs + dir.z * t.x, zs + dir.z * t.y);
}

vec3 rpw_normal_at(vec2 uv, vec2 texel, vec2 tanxy, vec3 P) {
    // Two wide baselines, summed: the object's depth has a little per-pixel
    // jitter, and a normal from close neighbours turns it into stripes.
    vec3 dx = vec3(0.0);
    vec3 dy = vec3(0.0);
    for (int s = 1; s <= 2; s++) {
        vec2 o = texel * (4.0 * float(s));
        dx += rpw_surface(uv + vec2(o.x, 0.0), tanxy, P) - rpw_surface(uv - vec2(o.x, 0.0), tanxy, P);
        dy += rpw_surface(uv + vec2(0.0, o.y), tanxy, P) - rpw_surface(uv - vec2(0.0, o.y), tanxy, P);
    }
    vec3 n = cross(dx, dy);
    if (dot(n, n) < 1e-14) return vec3(0.0);
    n = normalize(n);
    return dot(n, P) > 0.0 ? -n : n;
}

// The pull of a gravity lens, in the object's own units: the Schwarzschild
// radius its OWN code bends rays with (an object writes 1.5 x this into its
// photon equation), so the world it bends and the disk it draws agree.
const float RPW_GRAV_RS = 0.13;

// The scene bent around a gravity lens of radius R at C, for the eye ray v.
// A ray passing b radii from the middle is turned toward it by the thin-lens
// angle 2 rs / b, eased to nothing over the outer part of the sphere so the
// rim leaves no seam. (Counting only the path inside the sphere, a square
// root, also reaches zero at the rim, but so steeply that the rim showed as a
// hard line wherever the horizon crossed it.) The
// scene is then read where the turned ray points, as if it were far away: past
// the Einstein ring that is the far side's image, mirrored, and the world just
// outside the ring is stretched into an arc around it.
vec3 rpw_gravity_scene(vec3 v, vec3 C, float R, vec2 tanxy, vec2 texel) {
    float dist = length(C);
    vec3 c = C / dist;
    float cosT = clamp(dot(v, c), -1.0, 1.0);
    float theta = acos(cosT);
    vec3 e = v - c * cosT;
    float el = length(e);
    e = el > 1e-6 ? e / el : normalize(cross(c, vec3(0.0, 1.0, 0.0)));
    float b = clamp(sin(theta) * dist / R, 1e-3, 1.0);
    float bend = 2.0 * RPW_GRAV_RS / b * (1.0 - smoothstep(0.45, 1.0, b));
    float beta = theta - min(bend, 1.4);
    vec3 d = c * cos(beta) + e * sin(beta);
    vec2 huv = rpw_project(d, tanxy);
    // Off the screen there is nothing to read: fold back in, which shows more
    // of the same world rather than a smeared edge.
    huv = 1.0 - abs(1.0 - mod(huv, 2.0));
    // Something nearer than the lens is not behind it and is not bent.
    if (rpw_linear(texture(MainDepthSampler, huv).r) < dist - R) huv = 1.0 - abs(1.0 - mod(rpw_project(v, tanxy), 2.0));
    // The clouds are their own layer, composited after this, so the ones
    // behind the lens are bent here with the rest (the pass's own sort then
    // hides the unbent copy behind the lens's depth).
    vec3 seen = vec3(0.0);
    for (int q = 0; q < 4; q++) {
        vec2 o = vec2(q == 0 || q == 3 ? 0.6 : -0.6, q < 2 ? 0.6 : -0.6) * texel;
        vec2 at = clamp(huv + o, vec2(0.0), vec2(1.0));
        vec3 here = texture(MainSampler, at).rgb;
        vec4 cloud = texture(CloudsSampler, at);
        if (cloud.a > 0.0 && rpw_linear(texture(CloudsDepthSampler, at).r) > dist - R) here = blend(here, cloud);
        seen += here * 0.25;
    }
    return seen;
}


float rpw_hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float rpw_noise3(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(rpw_hash3(i), rpw_hash3(i + vec3(1, 0, 0)), f.x),
                   mix(rpw_hash3(i + vec3(0, 1, 0)), rpw_hash3(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(rpw_hash3(i + vec3(0, 0, 1)), rpw_hash3(i + vec3(1, 0, 1)), f.x),
                   mix(rpw_hash3(i + vec3(0, 1, 1)), rpw_hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

vec3 rpw_interference(float nm, float cosT) {
    return 0.5 + 0.5 * cos(6.2831853 * 2.0 * 1.33 * nm * cosT / vec3(650.0, 532.0, 450.0));
}

// One wall of a soap film: p on the unit sphere, n toward the viewer. Nearly
// clear face-on; toward the rim a band of interference colour, then a thin
// bright outline; two small highlights where it mirrors the sun and the sky.
vec4 rpw_film(vec3 p, vec3 n, vec3 toEye, float strength) {
    float cosT = clamp(abs(dot(n, toEye)), 0.0, 1.0);
    float edge = 1.0 - cosT;
    float fresnel = 0.02 + 0.98 * pow(edge, 5.0);
    float swirl = rpw_noise3(p * 2.1);
    float nm = mix(280.0, 780.0, clamp(0.5 - 0.35 * p.y + 0.45 * (swirl - 0.5), 0.0, 1.0));
    vec3 r = reflect(-toEye, n);
    vec3 sky = mix(vec3(0.78, 0.86, 1.0), vec3(0.5, 0.68, 1.0), clamp(r.y, 0.0, 1.0));
    sky = r.y < 0.0 ? mix(vec3(0.78, 0.86, 1.0), vec3(0.36, 0.5, 0.26), clamp(-r.y * 3.0, 0.0, 1.0)) : sky;
    float band = smoothstep(0.42, 0.68, edge) * (1.0 - smoothstep(0.86, 0.95, edge));
    vec3 bands = clamp(rpw_interference(1050.0 + 200.0 * (swirl - 0.5), cosT) * 1.6 - 0.2, 0.0, 1.0);
    float rim = smoothstep(0.9, 0.99, edge);
    float sun = pow(max(dot(r, normalize(vec3(0.4, 0.8, 0.3))), 0.0), 1800.0) * 3.0;
    float window = smoothstep(0.985, 0.995, dot(r, normalize(vec3(-0.55, 0.6, 0.45))));
    vec3 col = mix(sky * mix(vec3(1.0), rpw_interference(nm, cosT) * 1.2, 0.6), bands, band);
    col = mix(col, vec3(1.0), rim * 0.85) + vec3(sun + window * 0.8);
    float a = fresnel * 0.1 + band * 0.2 * dot(bands, vec3(0.4)) + rim * 0.8 + sun + window * 0.4;
    return vec4(col, clamp(a * strength, 0.0, 1.0));
}

void main() {
    vec4 item = texture(ItemEntitySampler, texCoord);
    float flag = rpw_flag(item);
    if (flag == 0.0) {
        rpe_vanilla_main();
        return;
    }
    vec2 texel = 1.0 / vec2(textureSize(MainSampler, 0));
    vec2 tanxy = vec2(RPW_TAN * texel.y / texel.x, RPW_TAN);
    float itemDepth = texture(ItemEntityDepthSampler, texCoord).r;
    rpw_reversed = itemDepth > texture(MainDepthSampler, texCoord).r;
    float zObj = rpw_linear(itemDepth);
    vec3 P = rpw_view(texCoord, zObj, tanxy);
    float a = (floor(item.a * 255.0 + 0.5) - (flag == 2.0 ? 5.0 : (flag == 3.0 ? 7.0 : 3.0))) / 8.0 / 31.0;
    // Stored premultiplied by the alpha that was really blended.
    vec3 objColor = item.rgb / max(item.a, 1e-3);
    float sceneDepth = texture(MainDepthSampler, texCoord).r;
    vec3 scene = texture(MainSampler, texCoord).rgb;

    if (flag >= 2.0) {
        // A lens is drawn here, whole, as a sphere — not from the colour the
        // item shader left, which is only what a player without Improved
        // Transparency sees. The sphere is SOLVED from the object's own depth,
        // and so is the field of view, which a post pass is never told (a
        // player's FOV setting, sprinting, flying): assume the wrong one and
        // the sphere comes back an ellipsoid, and every pixel fits it a
        // little differently, which drew the far contact line in pieces.
        // A point at screen position (u, v) and depth z is (s u z, s v z, -z)
        // for the unknown s = tan(fov / 2), and it lies on the sphere when
        //   s^2 (u^2 + v^2) z^2 - 2 sCx u z - 2 sCy v z + 2 Cz z + k = -z^2
        // which is LINEAR in (s^2, sCx, sCy, Cz, k). Taking each neighbour's
        // equation minus this pixel's removes k, and six rings of
        // neighbours solve the rest. A ring half hidden (by the ground, or
        // past the rim) only loses points; it never bends the answer.
        // Lengths are in units of this pixel's depth, to keep floats sane.
        vec2 aspect = vec2(texel.y / texel.x, 1.0);
        vec2 uv0 = (texCoord * 2.0 - 1.0) * aspect;
        float w0 = dot(uv0, uv0);
        mat4 M = mat4(0.0);
        vec4 bsum = vec4(0.0);
        float count = 0.0;
        // The wide rings carry the answer: depth is only so precise, and
        // how far a point sags off flat grows with the square of its reach.
        for (int k = 0; k < 48; k++) {
            int ring = k / 8;
            float ang = float(k) * 0.7853982 + float(ring) * 0.1308997;
            float reach = 8.0 * pow(2.2, float(ring));
            // On a pixel's centre: a depth filtered between pixels is a chord,
            // not a point on the sphere.
            vec2 uv = (floor((texCoord + vec2(cos(ang), sin(ang)) * texel * reach) / texel) + 0.5) * texel;
            if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) continue;
            if (rpw_flag(texture(ItemEntitySampler, uv)) != flag) continue;
            float zeta = rpw_linear(texture(ItemEntityDepthSampler, uv).r) / zObj;
            vec2 ui = (uv * 2.0 - 1.0) * aspect;
            vec4 row = vec4(dot(ui, ui) * zeta * zeta - w0,
                            -2.0 * (ui.x * zeta - uv0.x),
                            -2.0 * (ui.y * zeta - uv0.y),
                            2.0 * (zeta - 1.0));
            M += outerProduct(row, row);
            bsum += row * (1.0 - zeta * zeta);
            count += 1.0;
        }
        vec4 sol = count >= 6.0 && abs(determinant(M)) > 1e-30 ? inverse(M) * bsum : vec4(0.0);
        float s = sqrt(max(sol.x, 0.0));
        // The FOV setting moves in whole degrees, and the solve is least sure
        // of exactly this number, so a near miss is snapped to the degree and
        // the centre solved again with it known: three unknowns rather than
        // four, which is what keeps the far contact line in one piece.
        // (Mid-sprint the FOV really is between degrees, and is left alone.)
        float fovDeg = 2.0 * degrees(atan(s));
        if (abs(fovDeg - floor(fovDeg + 0.5)) < 0.3) {
            s = tan(radians(floor(fovDeg + 0.5)) * 0.5);
            mat3 M3 = mat3(M[1].yzw, M[2].yzw, M[3].yzw);
            vec3 b3 = bsum.yzw - s * s * M[0].yzw;
            if (abs(determinant(M3)) > 1e-30) sol.yzw = inverse(M3) * b3;
        }
        if (s > 0.05 && s < 5.0) {
            tanxy = aspect * s;
            P = rpw_view(texCoord, zObj, tanxy);
        }
        vec3 C = vec3(sol.y / max(s, 1e-6), sol.z / max(s, 1e-6), sol.w) * zObj;
        float R = length(P - C);
        if (flag == 3.0 && s > 0.05 && s < 5.0 && R > 1e-4 && R < 1e4 && dot(C - P, P) > 0.0) {
            // A gravity lens: the world bent around it, and the object's own
            // drawing (its disk, its horizon) over that.
            vec3 gcol = mix(rpw_gravity_scene(normalize(P), C, R, tanxy, texel), objColor, a);
            color_layers[0] = vec4(gcol, 1.0);
            depth_layers[0] = itemDepth;
            active_layers = 1;
            try_insert(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);
            try_insert(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);
            try_insert(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);
            try_insert(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);
            vec3 gAccum = color_layers[0].rgb;
            for (int ii = 1; ii < active_layers; ++ii) {
                gAccum = blend(gAccum, color_layers[ii]);
            }
            fragColor = vec4(gAccum, 1.0);
            return;
        }
        if (flag == 2.0 && s > 0.05 && s < 5.0 && R > 1e-4 && R < 1e4 && dot(C - P, P) > 0.0) {
            vec3 v = normalize(P);
            vec2 hits = rpw_sphere_hits(v, C, R);
            vec3 front = v * hits.x;
            vec3 back = v * hits.y;
            vec3 nf = normalize(front - C);
            vec3 nb = normalize(C - back);
            float zFront = -front.z;
            float zBack = -back.z;
            float zScene = rpw_linear(sceneDepth);
            vec3 ob = (front - C) / R;

            // How far, in pixels, the scene is from each wall here: the depth
            // gap over how fast it changes from pixel to pixel, taking the
            // gentler side so a block edge next door is not read as contact.
            vec2 g0 = rpw_gaps(texCoord, tanxy, C, R);
            vec2 gxa = abs(rpw_gaps(texCoord + vec2(texel.x, 0.0), tanxy, C, R) - g0);
            vec2 gxb = abs(g0 - rpw_gaps(texCoord - vec2(texel.x, 0.0), tanxy, C, R));
            vec2 gya = abs(rpw_gaps(texCoord + vec2(0.0, texel.y), tanxy, C, R) - g0);
            vec2 gyb = abs(g0 - rpw_gaps(texCoord - vec2(0.0, texel.y), tanxy, C, R));
            vec2 slope = max(sqrt(min(gxa, gxb) * min(gxa, gxb) + min(gya, gyb) * min(gya, gyb)), vec2(1e-7));
            float frontPx = max(g0.x, 0.0) / slope.x;

            // What is behind, seen through it, bent the way the reference
            // bubble bends it: a wide-angle picture of the world, more bent
            // toward the rim, the colours fanned a touch there. The bend pushes
            // each ray outward along the surface (no critical angle, so no
            // mirrored ring), and the bent ray is FOLLOWED until it meets the
            // scene, a few secant steps against the depth buffer, so what is
            // seen and where the far wall meets the ground come from one ray
            // and line up.
            vec3 tangent = v - nf * dot(v, nf);
            vec3 rg = normalize(v + tangent * 0.38);
            float tb = max(-2.0 * dot(front - C, rg), 0.0);
            float t = tb;
            vec2 huv = texCoord;
            for (int it = 0; it < 5; it++) {
                vec3 Q = front + rg * t;
                huv = clamp(rpw_project(Q, tanxy), vec2(0.0), vec2(1.0));
                t = clamp(t + (rpw_linear(texture(MainDepthSampler, huv).r) + Q.z) / max(-rg.z, 0.25), 0.0, 400.0);
            }
            if (rpw_linear(texture(MainDepthSampler, huv).r) < zFront) huv = texCoord;
            // Four taps a little apart: the bent picture is the world shrunk,
            // and one tap per pixel of a shrunk grass texture is shimmer.
            vec2 fan = rpw_project(normalize(v + tangent * 0.395), tanxy) - rpw_project(rg, tanxy);
            vec3 seen = vec3(0.0);
            for (int q = 0; q < 4; q++) {
                vec2 o = vec2(q == 0 || q == 3 ? 0.6 : -0.6, q < 2 ? 0.6 : -0.6) * texel;
                seen += vec3(texture(MainSampler, clamp(huv + o + fan, vec2(0.0), vec2(1.0))).r,
                             texture(MainSampler, clamp(huv + o, vec2(0.0), vec2(1.0))).g,
                             texture(MainSampler, clamp(huv + o - fan, vec2(0.0), vec2(1.0))).b) * 0.25;
            }

            // The far wall first, where it is not below ground, then the near.
            vec3 col = seen;
            if (zBack < zScene) {
                vec4 far = rpw_film((back - C) / R, nb, -v, 0.25);
                col = mix(col, far.rgb, far.a);
            }
            vec4 near = rpw_film(ob, nf, -v, 1.0);
            col = mix(col, near.rgb, near.a);

            // The sky mirrored in the top of the film: a pale veil over the
            // upper part, strongest toward the rim.
            float veil = smoothstep(0.1, 0.95, ob.y) * (0.35 + 0.65 * (1.0 - abs(dot(nf, v))));
            col = mix(col, vec3(0.94, 0.93, 1.0), veil * 0.24);

            // Where it meets anything. Not a drawn outline: in the reference it
            // is light caught where the film meets the grass, a soft band that
            // is brightest where the film is seen edge-on (toward the sides)
            // and fades and breaks up across the middle. A uniform crisp ring
            // read as a diagram. The far wall's meeting is only a faint echo.
            float pxWorld = 2.0 * tanxy.y * texel.y;
            float backW = pxWorld * max(-(front + rg * tb).z, 0.0) * 4.0 / max(-rg.z, 0.25);
            float backLine = (1.0 - smoothstep(0.0, backW, abs(t - tb))) * step(0.001, tb);
            float edgeOn = smoothstep(0.2, 0.85, 1.0 - abs(dot(nf, v)));
            float breakUp = smoothstep(0.25, 0.75, rpw_noise3(ob * 3.5));
            float frontLine = ((1.0 - smoothstep(1.0, 3.0, frontPx)) * 0.75 + exp(-frontPx / 7.0) * 0.4)
                * mix(0.12, 1.0, edgeOn) * mix(0.45, 1.0, breakUp);
            col = mix(col, vec3(0.88, 1.0, 0.84), clamp(frontLine + backLine * 0.28 * edgeOn, 0.0, 1.0));

            color_layers[0] = vec4(col, 1.0);
            depth_layers[0] = itemDepth;
            active_layers = 1;
            try_insert(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);
            try_insert(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);
            try_insert(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);
            try_insert(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);
            vec3 lensAccum = color_layers[0].rgb;
            for (int ii = 1; ii < active_layers; ++ii) {
                lensAccum = blend(lensAccum, color_layers[ii]);
            }
            fragColor = vec4(lensAccum, 1.0);
            return;
        }
    }

    // The contact line: the scene within a hair behind the object's surface.
    float gap = rpw_linear(sceneDepth) - zObj;
    float width = 0.05 + zObj * 0.003;
    // Only where the object is really there: a faint glow or a halo of sparks
    // touching the ground is not a surface the ground cuts through, and a line
    // across the floor under it reads as a seam.
    float line = exp(-max(gap, 0.0) / width) * smoothstep(0.2, 0.6, a);
    vec3 lineColor = mix(vec3(1.0), objColor, 0.3) * line;

    color_layers[0] = vec4(scene, 1.0);
    depth_layers[0] = sceneDepth;
    active_layers = 1;
    try_insert(texture(TranslucentSampler, texCoord), texture(TranslucentDepthSampler, texCoord).r);
    try_insert(vec4(item.rgb + lineColor, min(1.0, item.a + line * 0.5)), itemDepth);
    try_insert(texture(ParticlesSampler, texCoord), texture(ParticlesDepthSampler, texCoord).r);
    try_insert(texture(WeatherSampler, texCoord), texture(WeatherDepthSampler, texCoord).r);
    try_insert(texture(CloudsSampler, texCoord), texture(CloudsDepthSampler, texCoord).r);
    vec3 accum = color_layers[0].rgb;
    for (int ii = 1; ii < active_layers; ++ii) {
        accum = blend(accum, color_layers[ii]);
    }
    fragColor = vec4(accum, 1.0);
}
