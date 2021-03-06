//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.0.3-b01-fcs 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2009.01.29 at 01:15:48 PM EST 
//


package org.opennms.netmgt.provision.persist.requisition;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import org.apache.commons.lang.builder.CompareToBuilder;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.log4j.Category;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.provision.persist.OnmsNodeRequisition;
import org.opennms.netmgt.provision.persist.RequisitionVisitor;


@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name="model-import")
public class Requisition implements Serializable, Comparable<Requisition> {
    private static final long serialVersionUID = 1L;

    @XmlTransient
    private Map<String, OnmsNodeRequisition> m_nodeReqs = new LinkedHashMap<String, OnmsNodeRequisition>();
    
    @XmlElement(name="node")
    protected List<RequisitionNode> m_nodes;
    
    @XmlAttribute(name="date-stamp")
    protected XMLGregorianCalendar m_dateStamp;
    
    @XmlAttribute(name="foreign-source")
    protected String m_foreignSource;
    
    @XmlAttribute(name="last-import")
    protected XMLGregorianCalendar m_lastImport;

    public RequisitionNode getNode(String foreignId) {
        if (m_nodes != null) {
            for (RequisitionNode n : m_nodes) {
                if (n.getForeignId().equals(foreignId)) {
                    System.err.println("returning node '" + n + "' for foreign id '" + foreignId + "'");
                    return n;
                }
            }
        }
        return null;
    }

    public void deleteNode(String foreignId) {
        if (m_nodes != null) {
            Iterator<RequisitionNode> i = m_nodes.iterator();
            while (i.hasNext()) {
                RequisitionNode n = i.next();
                if (n.getForeignId().equals(foreignId)) {
                    i.remove();
                    break;
                }
            }
        }
    }

    public List<RequisitionNode> getNodes() {
        if (m_nodes == null) {
            m_nodes = new ArrayList<RequisitionNode>();
        }
        return m_nodes;
    }

    public void setNodes(List<RequisitionNode> nodes) {
        m_nodes = nodes;
        updateNodeCache();
    }

    public void putNode(RequisitionNode node) {
        if (m_nodeReqs.containsKey(node.getForeignId())) {
            RequisitionNode n = m_nodeReqs.get(node.getForeignId()).getNode();
            m_nodes.remove(n);
        }
        m_nodes.add(node);
        m_nodeReqs.put(node.getForeignId(), new OnmsNodeRequisition(getForeignSource(), node));
    }

    public XMLGregorianCalendar getDateStamp() {
        return m_dateStamp;
    }

    public void setDateStamp(XMLGregorianCalendar value) {
        m_dateStamp = value;
    }

    public void updateDateStamp() {
        try {
            m_dateStamp = DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
        } catch (DatatypeConfigurationException e) {
            log().warn("unable to update datestamp", e);
        }
    }

    public String getForeignSource() {
        if (m_foreignSource == null) {
            return "imported:";
        } else {
            return m_foreignSource;
        }
    }

    public void setForeignSource(String value) {
        m_foreignSource = value;
    }

    public XMLGregorianCalendar getLastImport() {
        return m_lastImport;
    }

    public void setLastImport(XMLGregorianCalendar value) {
        m_lastImport = value;
    }

    /* Start non-JAXB methods */

    public Requisition() {
        updateNodeCache();
        updateDateStamp();
    }

    private void updateNodeCache() {
        m_nodeReqs.clear();
        if (m_nodes != null) {
            for (RequisitionNode n : m_nodes) {
                m_nodeReqs.put(n.getForeignId(), new OnmsNodeRequisition(getForeignSource(), n));
            }
        }
    }
    
    public void visit(RequisitionVisitor visitor) {
        if (m_nodeReqs.size() == 0 && m_nodes != null && m_nodes.size() > 0) {
            updateNodeCache();
        }

        visitor.visitModelImport(this);
        
        for (OnmsNodeRequisition nodeReq : m_nodeReqs.values()) {
            nodeReq.visit(visitor);
        }
        
        visitor.completeModelImport(this);
    }

    public OnmsNodeRequisition getNodeRequistion(String foreignId) {
        if (m_nodeReqs.size() == 0 && m_nodes != null && m_nodes.size() > 0) {
            updateNodeCache();
        }
        return m_nodeReqs.get(foreignId);
    }
    
    private Category log() {
        return ThreadCategory.getInstance(Requisition.class);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
            .append("foreign-source", getForeignSource())
            .append("date-stamp", getDateStamp())
            .append("last-import", getLastImport())
            .append("nodes", getNodes())
            .toString();
    }

    public int compareTo(Requisition obj) {
        return new CompareToBuilder()
            .append(getForeignSource(), obj.getForeignSource())
            .append(getDateStamp(), obj.getDateStamp())
            .append(getLastImport(), obj.getLastImport())
            .append(getNodes(), obj.getNodes())
            .toComparison();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Requisition) {
            Requisition other = (Requisition) obj;
            return new EqualsBuilder()
                .append(getForeignSource(), other.getForeignSource())
                /*
                .append(getDateStamp(), other.getDateStamp())
                .append(getLastImport(), other.getLastImport())
                .append(getNodes(), other.getNodes())
                */
                .isEquals();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(37, 29)
            .append(getForeignSource())
            .append(getDateStamp())
            .append(getLastImport())
            .append(getNodes())
            .toHashCode();
      }

}
