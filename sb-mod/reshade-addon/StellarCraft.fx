// The texture is supplied by StellarCraft.addon, which reads Minecraft's shared frame.
texture2D McPixels : STELLARCRAFT_UI;

sampler2D McSampler
{
    Texture = McPixels;
    MinFilter = POINT;
    MagFilter = POINT;
    MipFilter = POINT;
    AddressU = CLAMP;
    AddressV = CLAMP;
};

uniform float McActive < hidden = true; > = 0.0;
uniform float4 McRect < hidden = true; > = float4(0.0, 0.0, 1.0, 1.0);
// xy: occupied fraction of the fixed 3840x2160 texture; z: source rows are bottom-up.
uniform float4 McTextureInfo < hidden = true; > = float4(1.0, 1.0, 1.0, 0.0);
// xy: cursor in normalized backbuffer coordinates; z: visible.
uniform float4 McCursor < hidden = true; > = float4(0.0, 0.0, 0.0, 0.0);

void FullscreenVS(in uint id : SV_VertexID,
                  out float4 position : SV_Position,
                  out float2 uv : TEXCOORD)
{
    uv = float2((id << 1) & 2, id & 2);
    position = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
}

float4 CompositePS(float4 position : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
    if (McActive < 0.5)
        return 0.0;

    // Draw a small cursor in the effect itself, so MC GUIs work even if the game's
    // raw-input code hides the Windows cursor.
    if (McCursor.z > 0.5)
    {
        float2 pixel = (uv - McCursor.xy) * float2(BUFFER_WIDTH, BUFFER_HEIGHT);
        if (pixel.x >= 0.0 && pixel.y >= 0.0 && pixel.y < 17.0 &&
            pixel.x <= min(11.0, pixel.y * 0.65))
        {
            bool border = pixel.x < 1.5 || pixel.y < 1.5 ||
                          pixel.x > pixel.y * 0.65 - 1.5;
            return border ? float4(0.0, 0.0, 0.0, 1.0) : float4(1.0, 1.0, 1.0, 1.0);
        }
    }

    float2 local = (uv - McRect.xy) / McRect.zw;
    if (local.x < 0.0 || local.y < 0.0 || local.x >= 1.0 || local.y >= 1.0)
        return 0.0;

    float2 source = float2(local.x, McTextureInfo.z > 0.5 ? 1.0 - local.y : local.y);
    // The source occupies only a subrectangle of the fixed texture.
    source *= McTextureInfo.xy;
    source = min(source, McTextureInfo.xy - float2(0.5 / 3840.0, 0.5 / 2160.0));
    float4 pixel = tex2D(McSampler, source);
    // MC blends onto a transparent framebuffer. Its RGB is premultiplied by coverage.
    // Recover straight RGB for the pass's SRCALPHA blend, including opaque black borders.
    return float4(pixel.a > 0.0 ? pixel.rgb / pixel.a : 0.0, pixel.a);
}

technique StellarCraftUI
{
    pass
    {
        VertexShader = FullscreenVS;
        PixelShader = CompositePS;
        BlendEnable = true;
        SrcBlend = SRCALPHA;
        DestBlend = INVSRCALPHA;
        BlendOp = ADD;
        SrcBlendAlpha = ONE;
        DestBlendAlpha = INVSRCALPHA;
        BlendOpAlpha = ADD;
    }
}
