#!/usr/bin/env python3
from PIL import Image, ImageDraw, ImageFont

# Icon sizes for different densities
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

def create_icon(size, output_path, is_round=False):
    """Create a simple launcher icon with 'LH' text"""
    # Create image with blue background
    img = Image.new('RGB', (size, size), color='#2196F3')
    draw = ImageDraw.Draw(img)

    if is_round:
        # Draw a circle mask for round icon
        mask = Image.new('L', (size, size), 0)
        mask_draw = ImageDraw.Draw(mask)
        mask_draw.ellipse((0, 0, size, size), fill=255)

        # Apply circular mask
        img.putalpha(mask)

    # Draw 'LH' text in white
    try:
        # Try to use a system font
        font_size = int(size * 0.4)
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", font_size)
    except:
        # Fallback to default font
        font = ImageFont.load_default()

    text = "LH"
    # Get text bounding box
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]

    # Center the text
    x = (size - text_width) // 2
    y = (size - text_height) // 2 - bbox[1]

    draw.text((x, y), text, fill='white', font=font)

    # Save the image
    img.save(output_path, 'PNG')
    print(f"Created: {output_path}")

# Create icons for all densities
for density, size in sizes.items():
    # Regular icon
    create_icon(
        size,
        f'app/src/main/res/mipmap-{density}/ic_launcher.png',
        is_round=False
    )

    # Round icon
    create_icon(
        size,
        f'app/src/main/res/mipmap-{density}/ic_launcher_round.png',
        is_round=True
    )

print("\nAll launcher icons created successfully!")
