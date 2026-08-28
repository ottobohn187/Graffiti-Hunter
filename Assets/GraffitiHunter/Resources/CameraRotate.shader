Shader "Hidden/GraffitiHunter/RotateCamera"
{
    Properties
    {
        _MainTex ("Camera", 2D) = "black" {}
        _QuarterTurns ("Quarter Turns", Float) = 0
        _MirrorY ("Mirror Y", Float) = 0
    }
    SubShader
    {
        Cull Off ZWrite Off ZTest Always
        Pass
        {
            CGPROGRAM
            #pragma vertex vert_img
            #pragma fragment frag
            #include "UnityCG.cginc"

            sampler2D _MainTex;
            float _QuarterTurns;
            float _MirrorY;

            fixed4 frag(v2f_img i) : SV_Target
            {
                float2 uv = i.uv;
                if (_QuarterTurns > 0.5 && _QuarterTurns < 1.5)
                    uv = float2(1.0 - uv.y, uv.x);
                else if (_QuarterTurns > 1.5 && _QuarterTurns < 2.5)
                    uv = float2(1.0 - uv.x, 1.0 - uv.y);
                else if (_QuarterTurns > 2.5)
                    uv = float2(uv.y, 1.0 - uv.x);
                if (_MirrorY > 0.5) uv.y = 1.0 - uv.y;
                return tex2D(_MainTex, uv);
            }
            ENDCG
        }
    }
}
