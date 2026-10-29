import type { Edge } from './types';

export default function Wire(props: { edge: Edge; selected: boolean; path: string; select: () => void; remove: () => void }) {
  return <g class={`wire ${props.edge.status === 'quarantined' ? 'quarantined' : ''} ${props.selected ? 'selected' : ''}`}>
    <title>{props.edge.reason ?? `Input ${props.edge.port}`}</title>
    <path class="wire-line" d={props.path} />
    <path class="wire-hit" d={props.path} role="button" tabindex="0"
      aria-label={`Select wire ${props.edge.from} to ${props.edge.to}, input ${props.edge.port}${props.edge.reason ? `: ${props.edge.reason}` : ''}`}
      onPointerDown={event => { event.stopPropagation(); props.select(); }}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); props.select(); }
        if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); event.stopPropagation(); props.remove(); }
      }} />
  </g>;
}
