import ELK from 'elkjs/lib/elk.bundled.js'
import { END, START } from './graph.js'

const elk = new ELK()

export const NODE_SIZE = { width: 176, height: 54 }
export const TERMINAL_SIZE = { width: 72, height: 28 }

export async function layoutGraph(nodes, edges) {
  const graph = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'DOWN',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.layered.spacing.nodeNodeBetweenLayers': '52',
      'elk.spacing.nodeNode': '56',
      'elk.spacing.edgeNode': '26',
      'elk.spacing.edgeEdge': '16',
      'elk.layered.spacing.edgeNodeBetweenLayers': '22',
      'elk.layered.spacing.edgeEdgeBetweenLayers': '14',
      'elk.layered.cycleBreaking.strategy': 'MODEL_ORDER',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
      'elk.layered.crossingMinimization.forceNodeModelOrder': 'false',
    },
    children: nodes.map((node) => ({
      id: node.id,
      ...(node.id === START || node.id === END ? TERMINAL_SIZE : NODE_SIZE),
    })),
    edges: edges.map((edge) => ({ id: edge.id, sources: [edge.source], targets: [edge.target] })),
  }

  const result = await elk.layout(graph)

  const positions = new Map(result.children.map((child) => [child.id, { x: child.x, y: child.y, width: child.width, height: child.height }]))
  const routes = new Map()
  for (const edge of result.edges) {
    const section = edge.sections?.[0]
    if (!section) continue
    routes.set(edge.id, [section.startPoint, ...(section.bendPoints ?? []), section.endPoint])
  }

  return { positions, routes, width: result.width, height: result.height }
}
