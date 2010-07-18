/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.seam.xml.model;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.inject.Stereotype;
import javax.inject.Qualifier;
import javax.interceptor.InterceptorBinding;

import org.jboss.seam.xml.core.BeanResult;
import org.jboss.seam.xml.core.BeanResultType;
import org.jboss.seam.xml.core.GenericBeanResult;
import org.jboss.seam.xml.core.XmlResult;
import org.jboss.seam.xml.fieldset.FieldValueObject;
import org.jboss.seam.xml.parser.SaxNode;
import org.jboss.seam.xml.parser.namespace.CompositeNamespaceElementResolver;
import org.jboss.seam.xml.parser.namespace.NamespaceElementResolver;
import org.jboss.seam.xml.parser.namespace.RootNamespaceElementResolver;
import org.jboss.seam.xml.util.XmlConfigurationException;

/**
 * Builds an XML result from sax nodes
 * 
 * @author stuart
 * 
 */
public class ModelBuilder
{

   static final String ROOT_NAMESPACE = "urn:java:ee";

   static final String BEANS_ROOT_NAMESPACE = "http://java.sun.com/xml/ns/javaee";

   /**
    * builds an XML result from a parsed xml document
    */
   public XmlResult build(SaxNode root, String fileUrl)
   {

      Map<String, NamespaceElementResolver> resolvers = new HashMap<String, NamespaceElementResolver>();

      XmlResult ret = new XmlResult(fileUrl);

      if (!root.getName().equals("beans"))
      {
         throw new XmlConfigurationException("Wrong root element for XML config file, expected:<beans> found:" + root.getName(), root.getDocument(), root.getLineNo());
      }
      if (!(ROOT_NAMESPACE.equals(root.getNamespaceUri()) || BEANS_ROOT_NAMESPACE.equals(root.getNamespaceUri())))
      {
         throw new XmlConfigurationException("Wrong root namespace for XML config file, expected:" + ROOT_NAMESPACE + " or " + BEANS_ROOT_NAMESPACE + " found:" + root.getNamespaceUri(), root.getDocument(), root.getLineNo());
      }

      resolvers.put(ROOT_NAMESPACE, new RootNamespaceElementResolver());

      List<SaxNode> children = root.getChildren();
      for (SaxNode node : children)
      {
         try
         {
            // nodes with a null namespace are whitespace nodes etc
            if (node.getNamespaceUri() != null)
            {
               // ignore <alternatives> <interceptors> etc
               if (node.getNamespaceUri().equals(BEANS_ROOT_NAMESPACE))
               {
                  continue;
               }
               XmlItem rb = resolveNode(node, null, resolvers);
               // validateXmlItem(rb);
               addNodeToResult(ret, rb);
            }
         }
         catch (Exception e)
         {
            ret.addProblem(e.getMessage());
            e.printStackTrace();
         }
      }

      return ret;
   }

   @SuppressWarnings("unchecked")
   private void addNodeToResult(XmlResult ret, XmlItem xmlItem)
   {

      if (xmlItem.getType() == XmlItemType.CLASS || xmlItem.getType() == XmlItemType.ANNOTATION)
      {
         ResultType resultType = getItemType(xmlItem);
         // if we are configuring a bean
         if (resultType == ResultType.BEAN)
         {
            ClassXmlItem cxml = (ClassXmlItem) xmlItem;
            // get the AnnotatedType information
            BeanResult<?> beanResult = cxml.createBeanResult();
            ret.addBean(beanResult);
            // <override> or <speciailizes> need to veto the bean
            if (beanResult.getBeanType() != BeanResultType.ADD)
            {
               ret.addVeto(beanResult.getType());
            }
            // get all the field values from the bean
            Set<String> configuredFields = new HashSet<String>();
            List<FieldValueObject> fields = new ArrayList<FieldValueObject>();
            for (FieldValueXmlItem xi : cxml.getChildrenOfType(FieldValueXmlItem.class))
            {
               FieldValueObject f = xi.getFieldValue();
               if (f != null)
               {
                  fields.add(f);
                  configuredFields.add(xi.getFieldName());
               }
            }

            for (FieldValueXmlItem f : cxml.getShorthandFieldValues())
            {
               if (configuredFields.contains(f.getFieldName()))
               {
                  throw new XmlConfigurationException("Field configured in two places: " + cxml.getJavaClass().getName() + "." + f.getFieldName(), cxml.getDocument(), cxml.getLineno());
               }
               fields.add(f.getFieldValue());
            }

            if (!fields.isEmpty())
            {
               if (xmlItem.getJavaClass().isInterface())
               {
                  ret.addInterfaceFieldValues(beanResult.getType(), fields);
               }
               else
               {
                  ret.addFieldValue(beanResult, fields);
               }
            }
         }
         else if (resultType == ResultType.QUALIFIER)
         {
            ret.addQualifier((Class) xmlItem.getJavaClass());
         }
         else if (resultType == ResultType.INTERCEPTOR_BINDING)
         {
            ret.addInterceptorBinding((Class) xmlItem.getJavaClass());
         }
         else if (resultType == ResultType.STEREOTYPE)
         {
            addStereotypeToResult(ret, xmlItem);
         }
      }
      else if (xmlItem.getType() == XmlItemType.GENERIC_BEAN)
      {

         GenericBeanXmlItem item = (GenericBeanXmlItem) xmlItem;
         Set<BeanResult<?>> classes = new HashSet<BeanResult<?>>();
         for (ClassXmlItem c : xmlItem.getChildrenOfType(ClassXmlItem.class))
         {
            BeanResult<?> br = c.createBeanResult();
            if (br.getBeanType() != BeanResultType.ADD)
            {
               ret.addVeto(br.getType());
            }
            classes.add(br);
         }
         ret.addGenericBean(new GenericBeanResult(item.getJavaClass(), classes));
      }
   }

   /**
    * resolves the appropriate java elements from the xml
    */
   protected XmlItem resolveNode(SaxNode node, XmlItem parent, Map<String, NamespaceElementResolver> resolvers)
   {
      NamespaceElementResolver resolver = resolveNamepsace(node.getNamespaceUri(), resolvers);

      XmlItem ret = resolver.getItemForNamespace(node, parent);

      if (ret == null)
      {
         throw new XmlConfigurationException("Could not resolve node " + node.getName() + " in namespace " + node.getNamespaceUri(), node.getDocument(), node.getLineNo());
      }
      List<SaxNode> children = node.getChildren();
      for (SaxNode n : children)
      {
         if (n.getNamespaceUri() != null)
         {
            XmlItem rb = resolveNode(n, ret, resolvers);
            ret.addChild(rb);
         }
      }
      ret.resolveChildren();
      return ret;

   }

   protected NamespaceElementResolver resolveNamepsace(String namespaceURI, Map<String, NamespaceElementResolver> resolvers)
   {
      if (resolvers.containsKey(namespaceURI))
      {
         return resolvers.get(namespaceURI);
      }
      String ns = namespaceURI.replaceFirst("urn:java:", "");
      CompositeNamespaceElementResolver res = new CompositeNamespaceElementResolver(ns.split(":"));
      resolvers.put(namespaceURI, res);
      return res;
   }

   /**
    * Determines the type of an element by examining its child nodes
    */
   protected ResultType getItemType(XmlItem item)
   {

      ResultType ret = null;
      for (AnnotationXmlItem it : item.getChildrenOfType(AnnotationXmlItem.class))
      {
         if (it.getJavaClass() == InterceptorBinding.class)
         {
            if (ret != null)
            {
               throw new XmlConfigurationException("Element cannot be both an INTERCEPTOR_BINDING and a " + ret.toString(), item.getDocument(), item.getLineno());
            }
            else
            {
               ret = ResultType.INTERCEPTOR_BINDING;
            }
         }
         else if (it.getJavaClass() == Qualifier.class)
         {
            if (ret != null)
            {
               throw new XmlConfigurationException("Element cannot be both an QUALIFIER and a " + ret.toString(), item.getDocument(), item.getLineno());
            }
            else
            {
               ret = ResultType.QUALIFIER;
            }
         }
         else if (it.getJavaClass() == Stereotype.class)
         {
            if (ret != null)
            {
               throw new XmlConfigurationException("Element cannot be both an STEREOTYPE and a " + ret.toString(), item.getDocument(), item.getLineno());
            }
            else
            {
               ret = ResultType.STEREOTYPE;
            }
         }
      }

      if (ret == null)
      {
         ret = ResultType.BEAN;
      }
      return ret;
   }

   @SuppressWarnings("unchecked")
   void addStereotypeToResult(XmlResult ret, XmlItem rb)
   {

      Annotation[] values = new Annotation[rb.getChildren().size()];
      int count = 0;
      for (XmlItem item : rb.getChildren())
      {
         if (item.getType() == XmlItemType.ANNOTATION)
         {
            Annotation a = AnnotationUtils.createAnnotation((AnnotationXmlItem) item);
            values[count] = a;
         }
         else
         {
            throw new XmlConfigurationException("Setereotype " + rb.getJavaClass() + " has an item that does not represent an annotation in its XML configurations", rb.getDocument(), rb.getLineno());
         }
         count++;
      }
      ret.addStereotype((Class) rb.getJavaClass(), values);

   }

   public void validateXmlItem(XmlItem item)
   {
      Set<XmlItemType> allowed = item.getAllowedItem();
      for (XmlItem i : item.getChildren())
      {
         if (!allowed.contains(item.getType()))
         {
            throw new XmlConfigurationException("Item " + item.getType() + " is not allowed to contain " + i.getType(), item.getDocument(), item.getLineno());
         }
         validateXmlItem(i);
      }
   }

}
