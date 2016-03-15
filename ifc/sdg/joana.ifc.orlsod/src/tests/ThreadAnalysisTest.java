package tests;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.ext.DOTExporter;
import org.jgrapht.ext.EdgeNameProvider;
import org.jgrapht.ext.VertexNameProvider;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;

import edu.kit.joana.graph.dominators.IInterprocDominators;
import edu.kit.joana.graph.dominators.InterprocDominators2;
import edu.kit.joana.graph.dominators.JoanaCFGAdapter;
import edu.kit.joana.graph.dominators.VirtualEdge;
import edu.kit.joana.ifc.sdg.graph.SDG;
import edu.kit.joana.ifc.sdg.graph.SDGEdge;
import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.joana.ifc.sdg.graph.SDGSerializer;
import edu.kit.joana.ifc.sdg.graph.slicer.graph.CFG;
import edu.kit.joana.ifc.sdg.graph.slicer.graph.VirtualNode;
import edu.kit.joana.ifc.sdg.graph.slicer.graph.building.ICFGBuilder;
import edu.kit.joana.ifc.sdg.graph.slicer.graph.threads.PreciseMHPAnalysis;
import edu.kit.joana.ifc.sdg.graph.slicer.graph.threads.ThreadRegion;
import edu.kit.joana.ifc.sdg.graph.slicer.graph.threads.ThreadsInformation.ThreadInstance;
import edu.kit.joana.ifc.sdg.mhpoptimization.CSDGPreprocessor;
import edu.kit.joana.ifc.sdg.util.sdg.GraphModifier;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;

public class ThreadAnalysisTest {
	public static void main(String[] args) throws IOException,
			ClassHierarchyException, UnsoundGraphException, CancelException {
		SDG sdg = JoanaRunner.buildSDG();
		CSDGPreprocessor.preprocessSDG(sdg);
		PreciseMHPAnalysis mhp = PreciseMHPAnalysis.analyze(sdg);
		PrintWriter pw = new PrintWriter(JoanaRunner.PDG_FILE);
		SDGSerializer.toPDGFormat(sdg, pw);
		pw.close();
//		CFG icfg = ReducedCFGBuilder.extractReducedCFG(sdg, new SDGNodePredicate() {
//
//			@Override
//			public boolean isInteresting(SDGNode node) {
//				return (node.getKind() == SDGNode.Kind.ENTRY
//						|| node.getKind() == SDGNode.Kind.EXIT
//						|| BytecodeLocation.isCallRetNode(node)
//						|| node.getBytecodeIndex() >= 0)
//						&& !sdg.getEntry(node).getBytecodeMethod().contains("fakeRootMethod")
//						&& !sdg.getEntry(node).getBytecodeMethod().contains("fakeWorldClinit")
//						&& !sdg.getEntry(node).getBytecodeMethod().contains("clinit");
//			}
//
//		});
		CFG icfg = ICFGBuilder.extractICFGIncludingJoins(sdg);
		JoanaCFGAdapter icfg2 = new JoanaCFGAdapter(icfg);
		DOTExporter<VirtualNode, VirtualEdge> exporterCFG = new DOTExporter<VirtualNode, VirtualEdge>(
				new VertexNameProvider<VirtualNode>() {

					@Override
					public String getVertexName(VirtualNode tr) {
						return Integer.toString(tr.getNumber() * icfg2.vertexSet().size() + tr.getNode().getId());
					}

				}, new VertexNameProvider<VirtualNode>() {

					@Override
					public String getVertexName(VirtualNode tr) {
						return tr.toString();
					}

				}, new EdgeNameProvider<VirtualEdge>() {

					@Override
					public String getEdgeName(VirtualEdge e) {
						return e.getKind().toString();
					}

				});
		PrintWriter pw1 = new PrintWriter("controlFlow.dot");
		exporterCFG.export(pw1, icfg2.getUnderlyingGraph());
		System.out.print("\nrunning dominator analysis...");

		InterprocDominators2<VirtualNode, VirtualEdge> dom = new InterprocDominators2<VirtualNode, VirtualEdge>(new JoanaCFGAdapter(icfg));
		dom.runWorklist();
		System.out.println("done.");
		//dom.refineWithMHPInfo(mhp);
		DefaultDirectedGraph<ThreadInstance, ThreadInstanceEdge> threadCreationTree = new DefaultDirectedGraph<ThreadInstance, ThreadInstanceEdge>(
				ThreadInstanceEdge.class);
		for (ThreadInstance ti : sdg.getThreadsInfo()) {
			if (ti.getFork() == null)
				continue;
			for (int threadForker : ti.getFork().getThreadNumbers()) {
				ThreadInstance tiForker = sdg.getThreadsInfo().getThread(threadForker);
				threadCreationTree.addVertex(tiForker);
				threadCreationTree.addVertex(ti);
				threadCreationTree.addEdge(tiForker, ti, new ThreadInstanceEdge(tiForker, ti));
			}
		}
		for (ThreadInstance ti1 : sdg.getThreadsInfo()) {
			for (ThreadInstance ti2 : sdg.getThreadsInfo()) {
				ThreadInstance lca = findLowestCommonAncestor(ti1, ti2, threadCreationTree);
				if (lca != null) {
					System.out.println(String.format("lca(%d,%d) = %d", ti1.getId(), ti2.getId(), lca.getId()));
				}
			}
		}
		int noVertices = threadCreationTree.vertexSet().size();
		int noEdges = threadCreationTree.edgeSet().size();
		if (noVertices > 0 && noEdges != noVertices - 1)
			throw new RuntimeException("not a tree");
		System.out.print("building dominator graph...");
		DirectedGraph<VirtualNode, DefaultEdge> domGraph = new DefaultDirectedGraph<VirtualNode, DefaultEdge>(DefaultEdge.class);
		for (SDGNode n : icfg.vertexSet()) {
			for (int thread : n.getThreadNumbers()) {
				domGraph.addVertex(new VirtualNode(n, thread));
			}
		}
		for (VirtualNode n : domGraph.vertexSet()) {
			for (VirtualNode dn : dom.idoms(n)) {
				domGraph.addEdge(dn, n);
			}
		}
		DOTExporter<VirtualNode, DefaultEdge> exporter = new DOTExporter<VirtualNode, DefaultEdge>(
				new VertexNameProvider<VirtualNode>() {

					@Override
					public String getVertexName(VirtualNode tr) {
						return Integer.toString(tr.getNumber() * icfg.vertexSet().size() + tr.getNode().getId());
					}

				}, new VertexNameProvider<VirtualNode>() {

					@Override
					public String getVertexName(VirtualNode tr) {
						return String.format("(%d, %d, %d) %s", tr.getNode().getId(), tr.getNumber(), mhp.getThreadRegion(tr).getID(), tr.getNode().getKind());
					}

				}, new EdgeNameProvider<DefaultEdge>() {

					@Override
					public String getEdgeName(DefaultEdge e) {
						return "";
					}

				});
		PrintWriter pw0 = new PrintWriter("domGraph.dot");
		exporter.export(pw0, domGraph);
	}

	private static Set<SDGNode> findSomeIDoms(SDGNode n1, int thread1, SDGNode n2, int thread2, DirectedGraph<ThreadInstance, ThreadInstanceEdge> tct, SDG sdg, PreciseMHPAnalysis mhp, IInterprocDominators dom) {
		Set<SDGNode> ret = new HashSet<SDGNode>();
		// 1.) get lowest common ancestor of n1 and n2
		ThreadInstance ti1 = sdg.getThreadsInfo().getThread(thread1);
		ThreadInstance ti2 = sdg.getThreadsInfo().getThread(thread2);
		ThreadInstance lca = findLowestCommonAncestor(ti1, ti2, tct);
		if (lca == null) {
			// one of the threads is the main thread
			// for now, we only consider the case where both threads are spawned by the main thread...
			return null;
		} else {
			Set<ThreadRegion> regionsLCA = new HashSet<ThreadRegion>();
			Set<VirtualNode> mhpNodes = new HashSet<VirtualNode>(); // nodes from the lca thread which mhp to ti1 or ti2
			for (ThreadRegion tr : mhp.getThreadRegions()) {
				if (tr.getThread() == lca.getId() && !mhp.isParallel(ti1.getEntry(), ti1.getId(), tr.getID()) && !mhp.isParallel(ti1.getEntry(), ti1.getId(), tr.getID())) {
					regionsLCA.add(tr);
				} else if (tr.getThread() == lca.getId()){
					for (SDGNode n : tr.getNodes()) {
						mhpNodes.add(new VirtualNode(n, tr.getThread()));
					}
				}
			}
			// find a node in one of the thread regions which only dominates nodes from thread regions which may happen in parallel with ti1 or ti2
			for (ThreadRegion tr : regionsLCA) {
				for (SDGNode n : tr.getNodes()) {
					Set<VirtualNode> d = dom.getDominated(new VirtualNode(n, tr.getThread()));
					if (isSubsetOf(d, mhpNodes)) {
						ret.add(n);
					}
				}
			}
			return ret;
		}
	}
	private static <T> boolean isSubsetOf(Set<T> s1, Set<T> s2) {
		for (T x : s1) {
			if (!s2.contains(x)) {
				return false;
			}
		}
		return true;
	}

	private static ThreadInstance findLowestCommonAncestor(ThreadInstance ti1, ThreadInstance ti2, DirectedGraph<ThreadInstance, ThreadInstanceEdge> tct) {
		if (ti1.equals(ti2)) {
			// ti1 is the same as ti2 --> lowest common ancestor is the forker of either
			return getForker(ti1, tct);
		} else {
			if (DijkstraShortestPath.findPathBetween(tct, ti1, ti2) != null) {
				// ti1 forks ti2 (indirectly) --> lowest common ancestor is forker of ti1
				return getForker(ti1, tct);
			} else if (DijkstraShortestPath.findPathBetween(tct, ti2, ti1) != null) {
				// ti2 forks ti1 (indirectly) --> lowest common ancestor is forker of ti2
				return getForker(ti2, tct);
			} else {

				// depth maps each common ancestor of ti1 and ti2 to the depth in the tct
				TObjectIntMap<ThreadInstance> depth = new TObjectIntHashMap<ThreadInstance>();
				// worklist contains outgoing edges of common ancestors of ti1 and ti2
				LinkedList<ThreadInstanceEdge> worklist = new LinkedList<ThreadInstanceEdge>();

				// first approximation: the root of the tct is a common ancestor of ti1 and ti2 of depth 0
				ThreadInstance lowestCommonAncestor = null;
				int maxLCADepth = 0;
				for (ThreadInstance ti : tct.vertexSet()) {
					if (tct.incomingEdgesOf(ti).isEmpty()) {
						depth.put(ti, 0);
						worklist.addAll(tct.outgoingEdgesOf(ti));
						lowestCommonAncestor = ti;
						break;
					}
				}
				while (!worklist.isEmpty()) {
					ThreadInstanceEdge next = worklist.poll();
					// if the target of the current edge is a common ancestor, we consider all outgoing edges
					// and possibly update the lowest common ancestor (if it is lower than the current lca)
					if (DijkstraShortestPath.findPathBetween(tct, next.getThread2(), ti1) != null && DijkstraShortestPath.findPathBetween(tct, next.getThread2(), ti2) != null) {
						ThreadInstance ca = next.getThread2();
						int d = depth.get(next.getThread1()) + 1;
						depth.put(ca, d);
						worklist.addAll(tct.outgoingEdgesOf(ca));
						if (d > maxLCADepth) {
							lowestCommonAncestor = ca;
							maxLCADepth = d;
						}
					}
				}
				return lowestCommonAncestor;
			}
		}
	}

	private static List<ThreadInstance> getChildren(ThreadInstance ti, DirectedGraph<ThreadInstance, ThreadInstanceEdge> tct) {
		List<ThreadInstance> ret = new LinkedList<ThreadInstance>();
		for (ThreadInstanceEdge e : tct.outgoingEdgesOf(ti)) {
			ret.add(e.getThread2());
		}
		return ret;
	}

	private static ThreadInstance getForker(ThreadInstance ti, DirectedGraph<ThreadInstance, ThreadInstanceEdge> tct) {
		if (tct.containsVertex(ti) && tct.incomingEdgesOf(ti).size() > 0) {
			ThreadInstanceEdge forkEdge = tct.incomingEdgesOf(ti).iterator().next();
			return forkEdge.getThread1();
		} else {
			// main thread
			return null;
		}
	}
	private static boolean directlyConnected(ThreadRegion region, SDGNode node,
			CFG icfg) {
		for (SDGNode rNode : region.getNodes()) {
			for (SDGEdge out : icfg.outgoingEdgesOf(rNode)) {
				if (out.getKind().isThreadEdge())
					continue;
				if (out.getTarget().equals(node))
					return true;
			}
		}
		return false;
	}

	private static <T> boolean isPrefixOf(List<T> l1, List<T> l2) {
		if (l1.size() >= l2.size()) {
			return false;
		} else {
			Iterator<T> l1Iter = l1.iterator();
			Iterator<T> l2Iter = l2.iterator();
			while (l1Iter.hasNext()) {
				T next1 = l1Iter.next();
				T next2 = l2Iter.next();
				if (!next1.equals(next2)) {
					return false;
				}
			}
			return true;
		}
	}

	private static class ThreadInstanceEdge {
		private final ThreadInstance ti1;
		private final ThreadInstance ti2;

		public ThreadInstanceEdge(ThreadInstance ti1, ThreadInstance ti2) {
			this.ti1 = ti1;
			this.ti2 = ti2;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ ((ti1 == null) ? 0 : ti1.hashCode());
			result = prime * result
					+ ((ti2 == null) ? 0 : ti2.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ThreadInstanceEdge other = (ThreadInstanceEdge) obj;
			if (ti1 == null) {
				if (other.ti1 != null)
					return false;
			} else if (!ti1.equals(other.ti1))
				return false;
			if (ti2 == null) {
				if (other.ti2 != null)
					return false;
			} else if (!ti2.equals(other.ti2))
				return false;
			return true;
		}

		public ThreadInstance getThread1() {
			return ti1;
		}

		public ThreadInstance getThread2() {
			return ti2;
		}
	}
}