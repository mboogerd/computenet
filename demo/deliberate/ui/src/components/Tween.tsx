import { createTween } from '../util/tween';

/** A percentage that glides between updates instead of jumping. */
export function TweenPct(props: { value: number }) {
  const v = createTween(() => props.value);
  return <>{`${Math.round(v() * 100)}%`}</>;
}
