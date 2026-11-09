/**
 * Barras verticales mínimas, sin librería de gráficos: son 4-6 valores como
 * mucho (meses de ventas), donde un SVG de una decena de líneas es más
 * simple y mantenible que una dependencia nueva (mismo criterio de ADR-010:
 * claridad sobre acabado visual, sin infraestructura sin justificar).
 */
export function MiniBarChart({ bars, formatValue }: { bars: Array<{ label: string; value: number }>; formatValue?: (value: number) => string }) {
  const max = Math.max(1, ...bars.map((bar) => bar.value));
  const width = 480;
  const height = 140;
  const barWidth = width / bars.length;
  const format = formatValue ?? ((value: number) => value.toLocaleString());

  return (
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Ventas por mes" className="mini-bar-chart">
      {bars.map((bar, index) => {
        const barHeight = (bar.value / max) * (height - 32);
        const x = index * barWidth + barWidth * 0.15;
        const barW = barWidth * 0.7;
        const y = height - 20 - barHeight;
        return (
          <g key={bar.label}>
            <rect x={x} y={y} width={barW} height={Math.max(barHeight, 1)} className="mini-bar-chart__bar" />
            <text x={x + barW / 2} y={y - 4} textAnchor="middle" className="mini-bar-chart__value">
              {format(bar.value)}
            </text>
            <text x={x + barW / 2} y={height - 4} textAnchor="middle" className="mini-bar-chart__label">
              {bar.label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
