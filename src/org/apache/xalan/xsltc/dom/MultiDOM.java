/*
 * @(#)$Id$
 *
 * The Apache Software License, Version 1.1
 *
 *
 * Copyright (c) 2001-2003 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Xalan" and "Apache Software Foundation" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    nor may "Apache" appear in their name, without prior written
 *    permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation and was
 * originally based on software copyright (c) 2001, Sun
 * Microsystems., http://www.sun.com.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 * @author Jacek Ambroziak
 * @author Morten Jorgensen
 * @author Erwin Bolwidt <ejb@klomp.org>
 *
 */

package org.apache.xalan.xsltc.dom;

import org.apache.xalan.xsltc.DOM;
import org.apache.xalan.xsltc.StripFilter;
import org.apache.xml.serializer.SerializationHandler;
import org.apache.xalan.xsltc.TransletException;
import org.apache.xalan.xsltc.runtime.BasisLibrary;
import org.apache.xalan.xsltc.runtime.Hashtable;
import org.apache.xml.dtm.DTM;
import org.apache.xml.dtm.DTMAxisIterator;
import org.apache.xml.dtm.DTMManager;
import org.apache.xml.dtm.ref.DTMAxisIteratorBase;
import org.apache.xml.dtm.ref.DTMDefaultBase;
import org.apache.xml.utils.SuballocatedIntVector;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class MultiDOM implements DOM {

    private static final int NO_TYPE = DOM.FIRST_TYPE - 2;
    private static final int INITIAL_SIZE = 4;
    
    private DOM[] _adapters;
    private DOMAdapter _main;
    private DTMManager _dtmManager;
    private int _free;
    private int _size;

    private Hashtable _documents = new Hashtable();

    private final class AxisIterator extends DTMAxisIteratorBase {
        // constitutive data
        private final int _axis;
        private final int _type;
        // implementation mechanism
        private DTMAxisIterator _source;
        private int _dtmId = -1;

        public AxisIterator(final int axis, final int type) {
            _axis = axis;
            _type = type;
        }

        public int next() {
            if (_source == null) {
                return(END);
            }
            return _source.next();
        }


        public void setRestartable(boolean flag) {
            if (_source != null) {
                _source.setRestartable(flag);
            }
        }

        public DTMAxisIterator setStartNode(final int node) {
            if (node == DTM.NULL) {
                return this;
            }

            int dom = node >>> DTMManager.IDENT_DTM_NODE_BITS;

            // Get a new source first time and when mask changes
            if (_source == null || _dtmId != dom) {
                if (_type == NO_TYPE) {
                    _source = _adapters[dom].getAxisIterator(_axis);
                } else if (_axis == Axis.CHILD) {
                    _source = _adapters[dom].getTypedChildren(_type);
                } else {
                    _source = _adapters[dom].getTypedAxisIterator(_axis, _type);
                }
            }

            _dtmId = dom;
            _source.setStartNode(node);
            return this;
        }

        public DTMAxisIterator reset() {
            if (_source != null) {
                _source.reset();
            }
            return this;
        }
    
        public int getLast() {
            if (_source != null) {
                return _source.getLast();
            }
            else {
                return END;
            }
        }

        public int getPosition() {
            if (_source != null) {
                return _source.getPosition();
            }
            else {
                return END;
            }
        }
    
        public boolean isReverse() {
	    return Axis.isReverse[_axis];
        }
    
        public void setMark() {
            if (_source != null) {
                _source.setMark();
            }
        }
    
        public void gotoMark() {
            if (_source != null) {
                _source.gotoMark();
            }
        }
    
        public DTMAxisIterator cloneIterator() {
            final AxisIterator clone = new AxisIterator(_axis, _type);
            if (_source != null) {
                clone._source = _source.cloneIterator();
            }
            clone._dtmId = _dtmId;
            return clone;
        }
    } // end of AxisIterator


    /**************************************************************
     * This is a specialised iterator for predicates comparing node or
     * attribute values to variable or parameter values.
     */
    private final class NodeValueIterator extends DTMAxisIteratorBase {

        private DTMAxisIterator _source;
        private String _value;
        private boolean _op;
        private final boolean _isReverse;
        private int _returnType = RETURN_PARENT;

        public NodeValueIterator(DTMAxisIterator source, int returnType,
                                 String value, boolean op) {
            _source = source;
            _returnType = returnType;
            _value = value;
            _op = op;
            _isReverse = source.isReverse();
        }

        public boolean isReverse() {
            return _isReverse;
        }
    
        public DTMAxisIterator cloneIterator() {
            try {
                NodeValueIterator clone = (NodeValueIterator)super.clone();
                clone._source = _source.cloneIterator();
                clone.setRestartable(false);
                return clone.reset();
            }
            catch (CloneNotSupportedException e) {
                BasisLibrary.runTimeError(BasisLibrary.ITERATOR_CLONE_ERR,
                                          e.toString());
                return null;
            }
        }


        public void setRestartable(boolean isRestartable) {
            _isRestartable = isRestartable;
            _source.setRestartable(isRestartable);
        }

        public DTMAxisIterator reset() {
            _source.reset();
            return resetPosition();
        }

        public int next() {

            int node;
            while ((node = _source.next()) != END) {
                String val = getStringValueX(node);
                if (_value.equals(val) == _op) {
                    if (_returnType == RETURN_CURRENT)
                        return returnNode(node);
                    else
                        return returnNode(getParent(node));
                }
            }
            return END;
        }

        public DTMAxisIterator setStartNode(int node) {
            if (_isRestartable) {
                _source.setStartNode(_startNode = node); 
                return resetPosition();
            }
            return this;
        }

        public void setMark() {
            _source.setMark();
        }

        public void gotoMark() {
            _source.gotoMark();
        }
    }                       

    public MultiDOM(DOM main) {
        _size = INITIAL_SIZE;
        _free = 1;
        _adapters = new DOM[INITIAL_SIZE];
        DOMAdapter adapter = (DOMAdapter)main;
        _adapters[0] = adapter;
        _main = adapter;
        DOM dom = adapter.getDOMImpl();
        if (dom instanceof DTMDefaultBase) {
            _dtmManager = ((DTMDefaultBase)dom).getManager();
        }

        // %HZ% %REVISIT% Is this the right thing to do here?  In the old
        // %HZ% %REVISIT% version, the main document did not get added through
        // %HZ% %REVISIT% a call to addDOMAdapter, which meant it couldn't be
        // %HZ% %REVISIT% found by a call to getDocumentMask.  The problem is
        // %HZ% %REVISIT% TransformerHandler is typically constructed with a
        // %HZ% %REVISIT% system ID equal to the stylesheet's URI; with SAX
        // %HZ% %REVISIT% input, it ends up giving that URI to the document.
        // %HZ% %REVISIT% Then, any references to document('') are resolved
        // %HZ% %REVISIT% using the stylesheet's URI.
        // %HZ% %REVISIT% MultiDOM.getDocumentMask is called to verify that
        // %HZ% %REVISIT% a document associated with that URI has not been
        // %HZ% %REVISIT% encountered, and that method ends up returning the
        // %HZ% %REVISIT% mask of the main document, when what we really what
        // %HZ% %REVISIT% is to read the stylesheet itself!
        addDOMAdapter(adapter, false);
    }

    public int nextMask() {
        return _free;
    }

    public void setupMapping(String[] names, String[] namespaces) {
        // This method only has a function in DOM adapters
    }

    public int addDOMAdapter(DOMAdapter adapter) {
        return addDOMAdapter(adapter, true);
    }

    private int addDOMAdapter(DOMAdapter adapter, boolean indexByURI) {
        // Add the DOM adapter to the array of DOMs
        DOM dom = adapter.getDOMImpl();
        
        int domNo = 1;
        int dtmSize = 1;
        SuballocatedIntVector dtmIds = null;
        if (dom instanceof DTMDefaultBase) {
            DTMDefaultBase dtmdb = (DTMDefaultBase)dom;
            dtmIds = dtmdb.getDTMIDs();
            dtmSize = dtmIds.size();
            domNo = dtmIds.elementAt(dtmSize-1) >>> DTMManager.IDENT_DTM_NODE_BITS;
        }
        else if (dom instanceof SimpleResultTreeImpl) {
            SimpleResultTreeImpl simpleRTF = (SimpleResultTreeImpl)dom;
            domNo = simpleRTF.getDocument() >>> DTMManager.IDENT_DTM_NODE_BITS;
        }
                  
        if (domNo >= _size) {
            int oldSize = _size;
            do {
            	_size *= 2;
            } while (_size <= domNo);
            
            final DOMAdapter[] newArray = new DOMAdapter[_size];
            System.arraycopy(_adapters, 0, newArray, 0, oldSize);
            _adapters = newArray;
        }
        
        _free = domNo + 1;
        
        if (dtmSize == 1) {
            _adapters[domNo] = adapter;
        }
        else if (dtmIds != null) {
            int domPos = 0;
            for (int i = dtmSize - 1; i >= 0; i--) {
                domPos = dtmIds.elementAt(i) >>> DTMManager.IDENT_DTM_NODE_BITS;
                _adapters[domPos] = adapter;
            }
            domNo = domPos;
        }

        // Store reference to document (URI) in hashtable
        if (indexByURI) {
            String uri = adapter.getDocumentURI(0);
            _documents.put(uri, new Integer(domNo));
        }
        
        // If the dom is an AdaptiveResultTreeImpl, we need to create a
        // DOMAdapter around its nested dom object (if it is non-null) and
        // add the DOMAdapter to the list.
        if (dom instanceof AdaptiveResultTreeImpl) {
            AdaptiveResultTreeImpl adaptiveRTF = (AdaptiveResultTreeImpl)dom;
            DOM nestedDom = adaptiveRTF.getNestedDOM();
            if (nestedDom != null) {
                DOMAdapter newAdapter = new DOMAdapter(nestedDom, 
                                                       adapter.getNamesArray(),
                                                       adapter.getNamespaceArray());
                addDOMAdapter(newAdapter);  
            } 
        }
        
        return domNo;
    }
        
    public int getDocumentMask(String uri) {
        Integer domIdx = (Integer)_documents.get(uri);
        if (domIdx == null) {
            return(-1);
        } else {
            return domIdx.intValue();
        }
    }
    
    public DOM getDOMAdapter(String uri) {
        Integer domIdx = (Integer)_documents.get(uri);
        if (domIdx == null) {
            return(null);
        } else {
            return(_adapters[domIdx.intValue()]);
        }
    }
    
    public int getDocument() 
    {
        return _main.getDocument();
    }

    public DTMManager getDTMManager() {
    	return _dtmManager;
    }

    /** 
      * Returns singleton iterator containing the document root 
      */
    public DTMAxisIterator getIterator() {
        // main source document @ 0
        return _main.getIterator();
    }
    
    public String getStringValue() {
        return _main.getStringValue();
    }
    
    public DTMAxisIterator getChildren(final int node) {
        return _adapters[getDTMId(node)].getChildren(node);
    }
    
    public DTMAxisIterator getTypedChildren(final int type) {
        return new AxisIterator(Axis.CHILD, type);
    }
    
    public DTMAxisIterator getAxisIterator(final int axis) {
        return new AxisIterator(axis, NO_TYPE);
    }
    
    public DTMAxisIterator getTypedAxisIterator(final int axis, final int type)
    {
        return new AxisIterator(axis, type);
    }

    public DTMAxisIterator getNthDescendant(int node, int n,
                                            boolean includeself)
    {
        return _adapters[getDTMId(node)].getNthDescendant(node, n, includeself);
    }

    public DTMAxisIterator getNodeValueIterator(DTMAxisIterator iterator,
                                                int type, String value,
                                                boolean op)
    {
        return(new NodeValueIterator(iterator, type, value, op));
    }

    public DTMAxisIterator getNamespaceAxisIterator(final int axis,
                                                    final int ns)
    {
        DTMAxisIterator iterator = _main.getNamespaceAxisIterator(axis, ns);
        return(iterator);        
    }

    public DTMAxisIterator orderNodes(DTMAxisIterator source, int node) {
        return _adapters[getDTMId(node)].orderNodes(source, node);
    }

    public int getExpandedTypeID(final int node) {
    	if (node != DTM.NULL) {
            return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].getExpandedTypeID(node);
    	}
    	else {
    	    return DTM.NULL;
    	}
    }

    public int getNamespaceType(final int node) {
        return _adapters[getDTMId(node)].getNamespaceType(node);
    }
    
    public int getNSType(int node)
   {
        return _adapters[getDTMId(node)].getNSType(node);
   }
    
    public int getParent(final int node) {
        if (node == DTM.NULL) {
            return DTM.NULL;
        }
        return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].getParent(node);
    }
    
    public int getAttributeNode(final int type, final int el) {
        if (el == DTM.NULL) {
            return DTM.NULL;
        }
        return _adapters[el >>> DTMManager.IDENT_DTM_NODE_BITS].getAttributeNode(type, el);
    }
    
    public String getNodeName(final int node) {
        if (node == DTM.NULL) {
            return "";
        }
        return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].getNodeName(node);
    }
    
    public String getNodeNameX(final int node) {
        if (node == DTM.NULL) {
            return "";
        }
        return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].getNodeNameX(node);
    }

    public String getNamespaceName(final int node) {
        if (node == DTM.NULL) {
            return "";
        }
        return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].getNamespaceName(node);
    }
    
    public String getStringValueX(final int node) {
        if (node == DTM.NULL) {
            return "";
        }
        return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].getStringValueX(node);
    }
    
    public void copy(final int node, SerializationHandler handler)
        throws TransletException
    {
        if (node != DTM.NULL) {
            _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].copy(node, handler);
        }
    }
    
    public void copy(DTMAxisIterator nodes, SerializationHandler handler)
            throws TransletException
    {
        int node;
        while ((node = nodes.next()) != DTM.NULL) {
            _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].copy(node, handler);
        }
    }


    public String shallowCopy(final int node, SerializationHandler handler)
            throws TransletException
    {
        if (node == DTM.NULL) {
            return "";
        }
        return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].shallowCopy(node, handler);
    }
    
    public boolean lessThan(final int node1, final int node2) {
        if (node1 == DTM.NULL) {
            return true;
        }
        if (node2 == DTM.NULL) {
            return false;
        }
        final int dom1 = getDTMId(node1);
        final int dom2 = getDTMId(node2);
        return dom1 == dom2 ? _adapters[dom1].lessThan(node1, node2)
                            : dom1 < dom2;
    }
    
    public void characters(final int textNode, SerializationHandler handler)
                 throws TransletException
    {
        if (textNode != DTM.NULL) {
            _adapters[textNode >>> DTMManager.IDENT_DTM_NODE_BITS].characters(textNode, handler);
        }
    }

    public void setFilter(StripFilter filter) {
        for (int dom=0; dom<_free; dom++) {
            if (_adapters[dom] != null) {
                _adapters[dom].setFilter(filter);
            }
        }
    }

    public Node makeNode(int index) {
        if (index == DTM.NULL) {
            return null;
        }
        return _adapters[getDTMId(index)].makeNode(index);
    }

    public Node makeNode(DTMAxisIterator iter) {
        // TODO: gather nodes from all DOMs ?
        return _main.makeNode(iter);
    }

    public NodeList makeNodeList(int index) {
        if (index == DTM.NULL) {
            return null;
        }
        return _adapters[getDTMId(index)].makeNodeList(index);
    }

    public NodeList makeNodeList(DTMAxisIterator iter) {
        // TODO: gather nodes from all DOMs ?
        return _main.makeNodeList(iter);
    }

    public String getLanguage(int node) {
        return _adapters[getDTMId(node)].getLanguage(node);
    }

    public int getSize() {
        int size = 0;
        for (int i=0; i<_size; i++) {
            size += _adapters[i].getSize();
        }
        return(size);
    }

    public String getDocumentURI(int node) {
        if (node == DTM.NULL) {
            node = DOM.NULL;
        }
        return _adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].getDocumentURI(0);
    }

    public boolean isElement(final int node) {
        if (node == DTM.NULL) {
            return false;
        }
        return(_adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].isElement(node));
    }

    public boolean isAttribute(final int node) {
        if (node == DTM.NULL) {
            return false;
        }
        return(_adapters[node >>> DTMManager.IDENT_DTM_NODE_BITS].isAttribute(node));
    }
    
    public int getDTMId(int nodeHandle)
    {
        if (nodeHandle == DTM.NULL)
            return 0;
        
        int id = nodeHandle >>> DTMManager.IDENT_DTM_NODE_BITS;
        while (id >= 2 && _adapters[id] == _adapters[id-1]) {
            id--;
        }
        return id;
    }
    
    public int getNodeIdent(int nodeHandle)
    {
        return _adapters[nodeHandle >>> DTMManager.IDENT_DTM_NODE_BITS].getNodeIdent(nodeHandle);
    }
    
    public int getNodeHandle(int nodeId)
    {
        return _main.getNodeHandle(nodeId);
    }
    
    public DOM getResultTreeFrag(int initSize, int rtfType)
    {
        return _main.getResultTreeFrag(initSize, rtfType);
    }
    
    public DOM getResultTreeFrag(int initSize, int rtfType, boolean addToManager)
    {
        return _main.getResultTreeFrag(initSize, rtfType, addToManager);
    }
    
    public DOM getMain()
    {
        return _main;
    }
    
    /**
     * Returns a DOMBuilder class wrapped in a SAX adapter.
     */
    public SerializationHandler getOutputDomBuilder()
    {
        return _main.getOutputDomBuilder();
    }

    public String lookupNamespace(int node, String prefix) 
        throws TransletException
    {
        return _main.lookupNamespace(node, prefix);
    }

    // %HZ% Does this method make any sense here???
    public String getUnparsedEntityURI(String entity) {
        return _main.getUnparsedEntityURI(entity);
    }

    // %HZ% Does this method make any sense here???
    public Hashtable getElementsWithIDs() {
        return _main.getElementsWithIDs();
    }
}
