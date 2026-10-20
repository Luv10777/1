package com.wuyao.growth.creative.image;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Art-direction notes for the poster styles the merchant can pick; written as visible facts, not praise. */
final class PosterStyles {
 private PosterStyles() {}
 private static final Map<String,String> GUIDES=Map.of(
  "杂志封面","Editorial magazine cover. Warm cream paper background; one hero subject in soft window light, 85mm, shallow depth of field; large high-contrast serif title across the top third; small monospace caption bottom-left. Palette: cream, ink black, one accent taken from the product. Avoid: stickers, badges, price bursts.",
  "日式留白","Quiet Japanese minimalism. Off-white linen background; subject small in the lower third; about 70% negative space; overcast diffused daylight; at most one short line of thin light sans-serif text. Palette: off-white, sand, pale grey. Avoid: saturated color, multiple text blocks.",
  "新中式","Neo-Chinese. Xuan rice-paper texture; sparse ink-wash branch or mountain; vertical brush-calligraphy title on the right; one small vermilion seal stamp; wide margins. Palette: ink, paper beige, vermilion. Avoid: dragons, gold clip-art, crowded red-and-gold decoration.",
  "港风复古","1980s Hong Kong film still. Tungsten street light and a lit shop sign; 35mm film grain; red-green contrast; condensed Song-style title. Palette: bottle green, signal red, warm cream. Avoid: modern UI elements, invented Cantonese slogans.",
  "大字报","Swiss grid promotional poster. Flat saturated color field; oversized heavy sans-serif headline filling about 40% of the frame; strict grid; one product cut-out; offer details only when the merchant provided them. Palette: one primary color, black, white. Avoid: photo backgrounds, invented discounts.",
  "胶片生活感","Lifestyle film photograph. Portra-like warm tones; natural late-afternoon light; hands using the product in a real shop setting; candid off-centre crop; small white caption in a corner. Avoid: studio backdrop, posed models, heavy retouching.",
  "手绘插画","Hand-drawn gouache illustration. Soft pastel palette; rounded hand-lettered title; simple friendly shapes; visible paper texture; the product drawn faithfully in its real color and shape. Avoid: 3D render look, generic mascots.",
  "暗调质感","Low-key studio still life. Charcoal background; single hard side light; deep shadows; rich surface texture on the subject; thin muted-gold serif title. Avoid: glossy luxury clichés, sparkles, lens flares.");
 private static final Pattern SEASONAL=Pattern.compile("应时·(\\p{IsHan}{2,6})");

 /** The guide for a known style, or empty for "帮我搭配" and styles saved before this list existed. */
 static Optional<String> guide(String style) {
   if(style==null) return Optional.empty();
   var seasonal=SEASONAL.matcher(style);
   if(seasonal.matches()) return Optional.of("Seasonal moment: "+seasonal.group(1)+". Express it through the real produce, light, temperature and colors of this time of year in China; keep the store's own product or service as the hero. Avoid: generic festival clip-art, invented events or offers.");
   return Optional.ofNullable(GUIDES.get(style));
 }
}
