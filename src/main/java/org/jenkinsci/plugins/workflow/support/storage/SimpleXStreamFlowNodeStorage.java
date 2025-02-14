/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.storage;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.core.JVM;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CompletionException;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Action;
import hudson.util.RobustReflectionConverter;
import hudson.util.XStream2;
import org.jenkinsci.plugins.workflow.support.PipelineIOUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * {@link FlowNodeStorage} that stores one node per one file.
 *
 * @author Kohsuke Kawaguchi
 */
public class SimpleXStreamFlowNodeStorage extends FlowNodeStorage {
    private final File dir;
    private final FlowExecution exec;

    private final LoadingCache<String,FlowNode> nodeCache = Caffeine.newBuilder()
            .softValues()
            .build(key -> SimpleXStreamFlowNodeStorage.this.load(key).node);

    private static final Logger LOGGER = Logger.getLogger(SimpleXStreamFlowNodeStorage.class.getName());

    /** Holds nodes that don't have to autopersist upon writing. */
    private transient HashMap<String, FlowNode> deferredWrite = null;

    private transient HashSet<String> delayAutopersistIds = null;

    public SimpleXStreamFlowNodeStorage(FlowExecution exec, File dir) {
        this.exec = exec;
        this.dir = dir;
    }

    @Override
    public FlowNode getNode(String id) throws IOException {
        try {
            if (deferredWrite != null) {
                FlowNode maybeOutput = deferredWrite.get(id);
                if (maybeOutput != null) {
                    return maybeOutput;
                }
            }
            return nodeCache.get(id);
        } catch (CompletionException x) {
            Throwable cause = x.getCause();
            if (cause instanceof NoSuchFileException) {
                LOGGER.finer("Tried to load FlowNode where file does not exist, for id "+id);
                // No file, no node
                return null;
            } else {
                throw new IOException(cause);
            }
        }
    }

    public void storeNode(@NonNull FlowNode n, boolean delayWritingActions) throws IOException {
        if (delayWritingActions) {
            if (deferredWrite == null) {
                deferredWrite = new HashMap<String, FlowNode>();
            }
            deferredWrite.put(n.getId(), n);
            if (delayAutopersistIds == null) {
                delayAutopersistIds = new HashSet<String>(2);
            }
            delayAutopersistIds.add(n.getId());
        } else {  // Flush, not that we still have to explicitly toggle autopersist for the node
            flushNode(n);
        }
    }

    @Override
    public void storeNode(FlowNode n) throws IOException {
        storeNode(n, false);
    }

    public void autopersist(@NonNull FlowNode n) throws IOException {
        if (deferredWrite != null && deferredWrite.containsKey(n.getId())) {
            flushNode(n);
        }
        if (delayAutopersistIds != null) {
            delayAutopersistIds.remove(n.getId());
        }
        // No-op if we weren't deferring a write of the node
    }

    /**
     * Persists a single FlowNode to disk (if not already persisted).
     * @param n Node to persist
     * @throws IOException
     */
    @Override
    public void flushNode(@NonNull FlowNode n) throws IOException {
        writeNode(n, n.getActions());
        if (deferredWrite != null) {
            deferredWrite.remove(n.getId());
        }
    }

    /** Force persisting any nodes that had writing deferred */
    @Override
    public void flush() throws IOException {
        if (deferredWrite != null && deferredWrite.isEmpty() == false) {
            Collection<FlowNode> toWrite = deferredWrite.values();
            for (FlowNode f : toWrite) {
                writeNode(f, f.getActions());
            }
            deferredWrite.clear();
        }
    }

    private File getNodeFile(String id) {
        return new File(dir,id+".xml");
    }

    public List<Action> loadActions(@NonNull FlowNode node) throws IOException {

        if (!getNodeFile(node.getId()).exists())
            return new ArrayList<Action>(); // not yet saved
        return load(node.getId()).actions();
    }

    private void writeNode(FlowNode node, List<Action> actions) throws IOException {
        nodeCache.put(node.getId(), node);
        PipelineIOUtils.writeByXStream(new Tag(node, actions), getNodeFile(node.getId()), XSTREAM, !this.isAvoidAtomicWrite());
    }

    /**
     * Just stores this one node, using the supplied actions.
     * GOTCHA: technically there's nothing ensuring that node.getActions() matches supplied actions.
     */
    public void saveActions(@NonNull FlowNode node, @NonNull List<Action> actions) throws IOException {
        if (delayAutopersistIds != null && delayAutopersistIds.contains(node.getId())) {
            deferredWrite.put(node.getId(), node);
        } else {
            writeNode(node, actions);
        }
    }

    /** Have we written everything to disk that we need to, or is there something waiting to be written */
    public boolean isPersistedFully() {
        return this.deferredWrite == null || this.deferredWrite.isEmpty();
    }

    private Tag load(String id) throws IOException {
        XmlFile nodeFile = new XmlFile(XSTREAM, getNodeFile(id));
        Tag v = (Tag) nodeFile.read();
        if (v.node == null) {
            throw new IOException("failed to load flow node from " + nodeFile + ": " + nodeFile.asString());
        }
        try {
            FlowNode$exec.set(v.node, exec);
        } catch (IllegalAccessException e) {
            throw (IllegalAccessError) new IllegalAccessError("Failed to set owner").initCause(e);
        }
        v.storeActions();
        for (FlowNodeAction a : Util.filter(v.actions(), FlowNodeAction.class)) {
            a.onLoad(v.node);
        }
        return v;
    }


    /**
     * To group node and their actions together into one object.
     */
    private static class Tag {
        final /* @NonNull except perhaps after deserialization */ FlowNode node;
        private final @CheckForNull Action[] actions;

        private Tag(@NonNull FlowNode node, @NonNull List<Action> actions) {
            this.node = node;
            this.actions = actions.isEmpty() ? null : actions.toArray(new Action[actions.size()]);
        }

        private void storeActions() {  // We've already loaded the actions, may as well store them to the FlowNode
            try {
                FlowNode_setActions.invoke(this.node, actions());
            } catch (InvocationTargetException|IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
        }

        public @NonNull List<Action> actions() {
            return actions != null ? Arrays.asList(actions) : Collections.<Action>emptyList();
        }
    }

    public static final XStream2 XSTREAM = new XStream2();

    private static final Field FlowNode$exec;
    private static final Field FlowNode$parents;
    private static final Field FlowNode$parentIds;
    private static final Method FlowNode_setActions;

    static {
        XSTREAM.registerConverter(new Converter() {
            private final RobustReflectionConverter ref = new RobustReflectionConverter(XSTREAM.getMapper(), JVM.newReflectionProvider());
            // IdentityHashMap could leak memory. WeakHashMap compares by equals, which will fail with NPE in FlowNode.hashCode.
            private final Map<FlowNode,String> ids = Caffeine.newBuilder().weakKeys().<FlowNode,String>build().asMap();
            @Override public boolean canConvert(Class type) {
                return FlowNode.class.isAssignableFrom(type);
            }
            @Override public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
                ref.marshal(source, writer, context);
            }
            @Override public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
                try {
                    FlowNode n = (FlowNode) ref.unmarshal(reader, context);
                    ids.put(n, reader.getValue());
                    try {
                        @SuppressWarnings("unchecked") List<FlowNode> parents = (List<FlowNode>) FlowNode$parents.get(n);
                        if (parents != null) {
                            @SuppressWarnings("unchecked") List<String> parentIds = (List<String>) FlowNode$parentIds.get(n);
                            assert parentIds == null;
                            parentIds = new ArrayList<String>(parents.size());
                            for (FlowNode parent : parents) {
                                String id = ids.get(parent);
                                assert id != null;
                                parentIds.add(id);
                            }
                            FlowNode$parents.set(n, null);
                            FlowNode$parentIds.set(n, parentIds);
                        }
                    } catch (Exception x) {
                        assert false : x;
                    }
                    return n;
                } catch (RuntimeException x) {
                    x.printStackTrace();
                    throw x;
                }
            }
        });

        // Aliases reduce the amount of data persisted to disk
        XSTREAM.alias("Tag", Tag.class);
        // Maybe alias for UninstantiatedDescribable too, if we add a structs dependency
        XSTREAM.aliasPackage("cps.n", "org.jenkinsci.plugins.workflow.cps.nodes");
        XSTREAM.aliasPackage("wf.a", "org.jenkinsci.plugins.workflow.actions");
        XSTREAM.aliasPackage("s.a", "org.jenkinsci.plugins.workflow.support.actions");
        XSTREAM.aliasPackage("cps.a", "org.jenkinsci.plugins.workflow.cps.actions");

        try {
            // TODO ugly, but we do not want public getters and setters for internal state.
            // Really FlowNode ought to have been an interface and the concrete implementations defined here, by the storage.
            FlowNode$exec = FlowNode.class.getDeclaredField("exec");
            FlowNode$exec.setAccessible(true);
            FlowNode$parents = FlowNode.class.getDeclaredField("parents");
            FlowNode$parents.setAccessible(true);
            FlowNode$parentIds = FlowNode.class.getDeclaredField("parentIds");
            FlowNode$parentIds.setAccessible(true);
            FlowNode_setActions = FlowNode.class.getDeclaredMethod("setActions", List.class);
            FlowNode_setActions.setAccessible(true);
        } catch (NoSuchFieldException|NoSuchMethodException e) {
            throw new Error(e);
        }
    }
}
