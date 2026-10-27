MIKU = (
    "Nakano Miku from The Quintessential Quintuplets, medium-length auburn brown hair with a soft rose pink tint, "
    "pinkish reddish brown, muted and desaturated, not orange, with very long side-swept bangs covering her right eye, "
    "light blue eyes, blue over-ear headphones hanging around her neck, "
    "tall slim build, narrow shoulders, slender arms, very long slim legs, "
    "wearing exactly two upper garments only: a plain solid light blue button-up collared shirt with no print, "
    "no pattern and no graphic on it, and a dark charcoal grey tailored suit blazer jacket with lapels and flap "
    "pockets worn over the shirt with the blazer sleeves on her arms, "
    "a green pleated mini skirt, black tights, brown penny loafers"
)

STYLE = (
    "Japanese visual novel character sprite, anime style, cel shading, clean lineart, "
    "character face, hairstyle, outfit and colors must match the reference image exactly"
)

TAIL_FULL = (
    "full body, head to toe, eye-level camera at chest height, straight-on frontal view, "
    "no high angle, no top-down perspective, no foreshortening, "
    "small head, head height about one sixth of her total height, slim neck, narrow shoulders, narrow hips, "
    "slender thighs, long slim legs, legs about half of her total height, slim overall silhouette, "
    "body proportions matching the second reference image, "
    "camera level with her waist, full length figure uncropped, "
    "soft colors, high quality character illustration, isolated on pure white background, no ground shadow"
)


def full(action: str) -> str:
    return f"{STYLE}, {MIKU}, {action}, {TAIL_FULL}"


def bust(action: str) -> str:
    return (
        f"{STYLE}, {MIKU}, {action}, close-up bust shot, head and shoulders visible, eye-level camera, "
        "no high angle, no top-down perspective, "
        "soft colors, high quality character illustration, isolated on pure white background, no ground shadow"
    )


SPRITES: list[tuple[str, str]] = [
    ("miku_normal", full("expressionless calm face, holding an open history book with completely blank plain pages, no printed text, with both hands in front of her chest, standing straight, neutral pose")),
    ("miku_slight_open", full("slightly widened eyes, faint expression of surprise and interest, lips slightly parted, standing straight")),
    ("miku_direct_look", bust("both eyes visible with bangs slightly parted, direct calm gaze at viewer, deep blue eyes, serious and quiet expression")),
    ("miku_wear_headphone", full("in the motion of putting on her blue headphones, one hand on the headphone, eyes lowered, quiet reserved expression, standing")),
    ("miku_awkward", full("slightly flushed cheeks, looking away to the side, embarrassed awkward expression, hands fidgeting with the headphone cord, standing")),
    ("miku_awkward_side", full("side profile view, ears slightly red, looking away from viewer, subtle embarrassed expression, hands at her sides, standing")),
    ("miku_kitchen", full("wearing a kitchen apron over her clothes, holding a spatula, back turned to viewer but head slightly turned, serious cooking expression, standing")),
    ("miku_turn_back", full("wearing a kitchen apron, turning her head back over her shoulder to look at viewer, side profile, bangs slightly parted, calm quiet gaze, standing")),
    ("miku_door", bust("without headphones, standing behind a slightly opened door, only half of her face visible from the door gap, messy bangs, hesitant expression, wanting to speak but holding back")),
    ("miku_faint_smile", full("without headphones, both eyes visible, very faint subtle smile, corners of her mouth slightly curved up, soft gentle gaze, standing")),
    ("miku_look_down", full("without headphones, head lowered, bangs covering most of her expression, downcast eyes, quiet vulnerable pose, standing")),
    ("miku_sit", full("sitting on a bench, hands placed on her knees, looking straight ahead not at viewer, calm neutral expression, seated pose, side-front angle")),
    ("miku_whisper", full("head slightly lowered, bangs covering most of her face, lips barely moving as if whispering, shy quiet expression, seated pose")),
    ("miku_challenge", full("one eyebrow slightly raised, faint challenging expression, eyes sharp with interest, lips in a small determined line, standing")),
    ("miku_serious", full("serious focused expression, looking straight ahead, headphone cord twirling around her finger, determined quiet gaze, seated pose")),
]

NEGATIVE = (
    "background scenery, gradient background, colored background, text, letters, garbled writing, "
    "watermark, logo, multiple characters, extra limbs, deformed hands, cropped head, cropped feet, harsh shadow, "
    "high angle view, top-down view, foreshortening, oversized head, large head, short legs, chibi proportions, "
    "orange hair, bright orange hair, cardigan, hoodie, thick padded sleeves, long torso, "
    "t-shirt under shirt, extra clothing layers, three layers of clothing, "
    "print on shirt, pattern on shirt, graphic on shirt, decorated shirt, "
    "wide hips, thick thighs, stocky body, heavy legs"
)
