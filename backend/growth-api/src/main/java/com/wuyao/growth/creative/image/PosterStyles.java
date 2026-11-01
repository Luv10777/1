package com.wuyao.growth.creative.image;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Medium and quality notes for general drawing; never adds marketing copy by default. */
final class PosterStyles {
 private PosterStyles() {}
 private static final Map<String,String> GUIDES=Map.of(
  "写实摄影","Natural photographic realism. Use a deliberate viewpoint, coherent perspective, plausible light direction and contact shadows. Preserve natural skin, fur, fabric and surface texture; distinguish the focal subject from the background. Avoid: waxy skin, excessive smoothing, oversharpened halos, implausible anatomy. Match framing and depth of field to the requested subject, not a fixed lens or commercial setup.",
  "电影质感","Cinematic visual storytelling. Choose an intentional framing and scene scale, foreground-to-background depth, motivated key light and controlled shadow falloff. Use a coherent color relationship and restrained highlight rolloff. Avoid: unrelated props, arbitrary lens flares and blanket teal-orange grading. Keep the requested subject and action clear.",
  "手绘水彩","Hand-painted watercolor. Transparent layered washes, soft pigment blooms, selective crisp edges, visible paper tooth and controlled color mixing. Use simplified shapes and detail concentrated around the focal subject. Avoid: plastic 3D surfaces, muddy indiscriminate texture and automatically added lettering.",
  "国风意境","East Asian ink-and-color visual language. Varied ink density, dry-brush edges, restrained mineral colors, deliberate empty space and balanced visual rhythm. Choose imagery from the user's subject instead of automatically adding seals, calligraphy or festival decoration. Avoid: generic red-and-gold ornament and uncontrolled ink clutter.",
  "极简留白","Minimal composition with purposeful negative space. Few well-defined shapes, clean silhouettes, controlled spacing, restrained palette and soft tonal separation. Preserve recognizable subject details; do not reduce every scene to a small centered product on white. Avoid: unnecessary ornaments, arbitrary labels and blankness that loses the requested content.",
  "胶片纪实","Documentary film-photograph treatment. Natural gestures and interactions, believable everyday textures, restrained film grain, gentle highlight rolloff and coherent ambient light. Use a candid viewpoint that serves the described scene. Avoid: waxy faces, heavy beauty retouching, staged advertising poses and automatic captions.",
  "平面设计","Graphic visual design. Strong shape hierarchy, controlled geometric relationships, confident cropping, a coherent palette and clean edges. Typography is included only when requested, with exact wording and clear placement. Avoid: random badges, invented promotional text and excessive decorative elements.",
  "暗调光影","Low-key visual treatment. Define the focal subject through selective directional light, readable shadow detail, restrained highlights and a controlled dark palette. Surface details and silhouette remain distinguishable. Avoid: crushed featureless blacks, arbitrary gold decoration and overexposed reflections.");
 private static final Map<String,String> LEGACY_NAMES=Map.of(
  "杂志封面","平面设计","日式留白","极简留白","新中式","国风意境","港风复古","电影质感",
  "大字报","平面设计","胶片生活感","胶片纪实","手绘插画","手绘水彩","暗调质感","暗调光影");
 private static final Pattern SEASONAL=Pattern.compile("应时·(\\p{IsHan}{2,6})");

 static Optional<String> guide(String style) {
   if(style==null) return Optional.empty();
   var seasonal=SEASONAL.matcher(style);
   if(seasonal.matches()) return Optional.of("Seasonal moment: "+seasonal.group(1)+". Express seasonal light, atmosphere, natural textures and colors in the user's requested subject or scene. Avoid: automatic festival clip-art, invented events or offers; do not force a store or product into the image.");
   return Optional.ofNullable(GUIDES.get(LEGACY_NAMES.getOrDefault(style,style)));
 }
}
