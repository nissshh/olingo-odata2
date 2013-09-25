/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 ******************************************************************************/
package org.apache.olingo.odata2.core.ep.consumer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.olingo.odata2.api.edm.Edm;
import org.apache.olingo.odata2.api.ep.EntityProviderException;
import org.apache.olingo.odata2.api.ep.EntityProviderReadProperties;
import org.apache.olingo.odata2.api.ep.entry.ODataEntry;
import org.apache.olingo.odata2.api.ep.feed.ODataFeed;
import org.apache.olingo.odata2.core.ep.aggregator.EntityInfoAggregator;
import org.apache.olingo.odata2.core.ep.feed.FeedMetadataImpl;
import org.apache.olingo.odata2.core.ep.feed.ODataFeedImpl;
import org.apache.olingo.odata2.core.ep.util.FormatXml;

/**
 * Atom/XML format reader/consumer for feeds.
 * 
 * {@link XmlFeedConsumer} instance use
 * {@link XmlEntryConsumer#readEntry(XMLStreamReader, EntityInfoAggregator, EntityProviderReadProperties)} for
 * read/consume of several entries.
 * 
 * 
 */
public class XmlFeedConsumer {

  /**
   * 
   * @param reader
   * @param eia
   * @param readProperties
   * @return {@link ODataFeed} object
   * @throws EntityProviderException
   */
  public ODataFeed readFeed(final XMLStreamReader reader, final EntityInfoAggregator eia,
      final EntityProviderReadProperties readProperties) throws EntityProviderException {
    try {
      // read xml tag
      reader.require(XMLStreamConstants.START_DOCUMENT, null, null);
      reader.nextTag();

      // read feed tag
      reader.require(XMLStreamConstants.START_ELEMENT, Edm.NAMESPACE_ATOM_2005, FormatXml.ATOM_FEED);
      Map<String, String> foundPrefix2NamespaceUri = extractNamespacesFromTag(reader);
      foundPrefix2NamespaceUri.putAll(readProperties.getValidatedPrefixNamespaceUris());
      checkAllMandatoryNamespacesAvailable(foundPrefix2NamespaceUri);
      EntityProviderReadProperties entryReadProperties =
          EntityProviderReadProperties.initFrom(readProperties).addValidatedPrefixes(foundPrefix2NamespaceUri).build();

      // read feed data (metadata and entries)
      return readFeedData(reader, eia, entryReadProperties);
    } catch (XMLStreamException e) {
      throw new EntityProviderException(EntityProviderException.EXCEPTION_OCCURRED.addContent(e.getClass()
          .getSimpleName()), e);
    }
  }

  /**
   * Read all feed specific data (like <code>inline count</code> and <code>next link</code>) as well as all feed entries
   * (<code>entry</code>).
   * 
   * @param reader xml stream reader with xml content to be read
   * @param eia entity infos for validation and mapping
   * @param entryReadProperties properties which are used for read of feed.
   * @return all feed specific data (like <code>inline count</code> and <code>next link</code>) as well as all feed
   * entries (<code>entry</code>).
   * @throws XMLStreamException if malformed xml is read in stream
   * @throws EntityProviderException if xml contains invalid data (based on odata specification and edm definition)
   */
  private ODataFeed readFeedData(final XMLStreamReader reader, final EntityInfoAggregator eia,
      final EntityProviderReadProperties entryReadProperties) throws XMLStreamException, EntityProviderException {
    FeedMetadataImpl metadata = new FeedMetadataImpl();
    XmlEntryConsumer xec = new XmlEntryConsumer();
    List<ODataEntry> results = new ArrayList<ODataEntry>();

    while (reader.hasNext() && !isFeedEndTag(reader)) {
      if (FormatXml.ATOM_ENTRY.equals(reader.getLocalName())) {
        ODataEntry entry = xec.readEntry(reader, eia, entryReadProperties);
        results.add(entry);
      } else if (FormatXml.M_COUNT.equals(reader.getLocalName())) {
        reader.require(XMLStreamConstants.START_ELEMENT, Edm.NAMESPACE_M_2007_08, FormatXml.M_COUNT);

        reader.next();
        if (reader.hasText()) {
          String inlineCountString = reader.getText();
          try {
            int inlineCountNumber = Integer.valueOf(inlineCountString);
            if (inlineCountNumber >= 0) {
              metadata.setInlineCount(inlineCountNumber);
            } else {
              throw new EntityProviderException(EntityProviderException.INLINECOUNT_INVALID
                  .addContent(inlineCountNumber));
            }
          } catch (NumberFormatException e) {
            throw new EntityProviderException(EntityProviderException.INLINECOUNT_INVALID.addContent(""), e);
          }
        }
      } else if (FormatXml.ATOM_LINK.equals(reader.getLocalName())) {
        reader.require(XMLStreamConstants.START_ELEMENT, Edm.NAMESPACE_ATOM_2005, FormatXml.ATOM_LINK);

        final String rel = reader.getAttributeValue(null, FormatXml.ATOM_REL);
        if (FormatXml.ATOM_NEXT_LINK.equals(rel)) {
          final String uri = reader.getAttributeValue(null, FormatXml.ATOM_HREF);
          metadata.setNextLink(uri);
        } else if (FormatXml.ATOM_DELTA_LINK.equals(rel)) {
          final String uri = reader.getAttributeValue(null, FormatXml.ATOM_HREF);
          metadata.setDeltaLink(uri);
        }

        reader.next();
      } else {
        reader.next();
      }
      readTillNextStartTag(reader);
    }
    return new ODataFeedImpl(results, metadata);
  }

  private void readTillNextStartTag(final XMLStreamReader reader) throws XMLStreamException {
    while (reader.hasNext() && !reader.isStartElement()) {
      reader.next();
    }
  }

  private boolean isFeedEndTag(final XMLStreamReader reader) {
    return reader.isEndElement()
        && Edm.NAMESPACE_ATOM_2005.equals(reader.getNamespaceURI())
        && FormatXml.ATOM_FEED.equals(reader.getLocalName());
  }

  /**
   * Maps all all found namespaces of current xml tag into a map.
   * 
   * @param reader xml reader with current position at a xml tag
   * @return map with all found namespaces of current xml tag
   */
  private Map<String, String> extractNamespacesFromTag(final XMLStreamReader reader) {
    // collect namespaces
    Map<String, String> foundPrefix2NamespaceUri = new HashMap<String, String>();
    int namespaceCount = reader.getNamespaceCount();
    for (int i = 0; i < namespaceCount; i++) {
      String namespacePrefix = reader.getNamespacePrefix(i);
      String namespaceUri = reader.getNamespaceURI(i);

      foundPrefix2NamespaceUri.put(namespacePrefix, namespaceUri);
    }
    return foundPrefix2NamespaceUri;
  }

  /**
   * 
   * @param foundPrefix2NamespaceUri
   * @throws EntityProviderException
   */
  private void checkAllMandatoryNamespacesAvailable(final Map<String, String> foundPrefix2NamespaceUri)
      throws EntityProviderException {
    if (!foundPrefix2NamespaceUri.containsValue(Edm.NAMESPACE_D_2007_08)) {
      throw new EntityProviderException(EntityProviderException.INVALID_NAMESPACE.addContent(Edm.NAMESPACE_D_2007_08));
    } else if (!foundPrefix2NamespaceUri.containsValue(Edm.NAMESPACE_M_2007_08)) {
      throw new EntityProviderException(EntityProviderException.INVALID_NAMESPACE.addContent(Edm.NAMESPACE_M_2007_08));
    } else if (!foundPrefix2NamespaceUri.containsValue(Edm.NAMESPACE_ATOM_2005)) {
      throw new EntityProviderException(EntityProviderException.INVALID_NAMESPACE.addContent(Edm.NAMESPACE_ATOM_2005));
    }
  }
}