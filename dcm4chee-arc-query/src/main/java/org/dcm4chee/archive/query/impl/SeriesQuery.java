/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.archive.query.impl;

import org.dcm4che3.data.Attributes;
import org.dcm4chee.archive.entity.Availability;
import org.dcm4chee.archive.entity.QSeries;
import org.dcm4chee.archive.entity.QSeriesQueryAttributes;
import org.dcm4chee.archive.entity.QStudy;
import org.dcm4chee.archive.entity.QStudyQueryAttributes;
import org.dcm4chee.archive.entity.Series;
import org.dcm4chee.archive.entity.SeriesQueryAttributes;
import org.dcm4chee.archive.entity.StudyQueryAttributes;
import org.dcm4chee.archive.entity.Utils;
import org.dcm4chee.archive.query.QueryContext;
import org.dcm4chee.archive.query.util.QueryBuilder;
import org.hibernate.ScrollableResults;
import org.hibernate.StatelessSession;

import com.mysema.query.BooleanBuilder;
import com.mysema.query.jpa.hibernate.HibernateQuery;
import com.mysema.query.types.Expression;
import com.mysema.query.types.Predicate;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 */
class SeriesQuery extends AbstractQuery<Series> {

    private static final Expression<?>[] SELECT = {
        QStudy.study.pk,                                                        // (0)
        QSeries.series.pk,                                                      // (1)
        QSeriesQueryAttributes.seriesQueryAttributes.numberOfInstances,         // (2)
        QStudyQueryAttributes.studyQueryAttributes.numberOfInstances,           // (3)
        QStudyQueryAttributes.studyQueryAttributes.numberOfSeries,              // (4)
        QStudyQueryAttributes.studyQueryAttributes.modalitiesInStudy,           // (5)
        QStudyQueryAttributes.studyQueryAttributes.sopClassesInStudy,           // (6)
        QSeriesQueryAttributes.seriesQueryAttributes.retrieveAETs,              // (7)
        QSeriesQueryAttributes.seriesQueryAttributes.externalRetrieveAET,       // (8)
        QSeriesQueryAttributes.seriesQueryAttributes.availability,              // (9)
        QueryBuilder.seriesAttributesBlob.encodedAttributes,                    // (10)
        QueryBuilder.studyAttributesBlob.encodedAttributes,                     // (11)
        QueryBuilder.patientAttributesBlob.encodedAttributes                    // (12)
    };

    private Long studyPk;
    private Attributes studyAttrs;

    public SeriesQuery(QueryContext context, StatelessSession session) {
        super(context, session, QSeries.series);
    }

    @Override
    protected Expression<?>[] select() {
        return SELECT;
    }

    @Override
    protected HibernateQuery applyJoins(HibernateQuery query) {
        query = QueryBuilder.applySeriesLevelJoins(query,
                context.getKeys(),
                context.getQueryParam());
        query = QueryBuilder.applyStudyLevelJoins(query,
                context.getKeys(),
                context.getQueryParam());
        query = QueryBuilder.applyPatientLevelJoins(query,
                context.getKeys(),
                context.getQueryParam());
        return query;
    }

    @Override
    protected Predicate predicate() {
        BooleanBuilder builder = new BooleanBuilder();
        QueryBuilder.addPatientLevelPredicates(builder,
                context.getPatientIDs(),
                context.getKeys(),
                context.getQueryParam());
        QueryBuilder.addStudyLevelPredicates(builder,
                context.getKeys(),
                context.getQueryParam());
        QueryBuilder.addSeriesLevelPredicates(builder,
                context.getKeys(),
                context.getQueryParam());
        return builder;
    }

    @Override
    public Attributes toAttributes(ScrollableResults results) {
        Long studyPk = results.getLong(0);
        Long seriesPk = results.getLong(1);
        Integer numberOfInstancesI = results.getInteger(2);
        int numberOfSeriesRelatedInstances;
        String retrieveAETs;
        String externalRetrieveAET;
        Availability availability;
        if (numberOfInstancesI != null) {
            numberOfSeriesRelatedInstances = numberOfInstancesI;
            if (numberOfSeriesRelatedInstances == 0)
                return null;

            retrieveAETs = results.getString(7);
            externalRetrieveAET = results.getString(8);
            availability = (Availability) results.get(9);
        } else {
            SeriesQueryAttributes seriesView = context.getQueryService()
                    .createSeriesView(seriesPk,  context.getQueryParam());
            numberOfSeriesRelatedInstances = seriesView.getNumberOfInstances();
            if (numberOfSeriesRelatedInstances == 0)
                return null;

            retrieveAETs = seriesView.getRawRetrieveAETs();
            externalRetrieveAET = seriesView.getExternalRetrieveAET();
            availability = seriesView.getAvailability();
        }

        byte[] seriesAttributes = results.getBinary(10);
        if (!studyPk.equals(this.studyPk)) {
            this.studyAttrs = toStudyAttributes(studyPk, results);
            this.studyPk = studyPk;
        }
        Attributes seriesAttrs = new Attributes();
        Utils.decodeAttributes(seriesAttrs, seriesAttributes);
        Attributes attrs = Utils.mergeAndNormalize(studyAttrs, seriesAttrs);
        Utils.setSeriesQueryAttributes(attrs, numberOfSeriesRelatedInstances);
        Utils.setRetrieveAET(attrs, retrieveAETs, externalRetrieveAET);
        Utils.setAvailability(attrs, availability);

        return attrs;
    }

    private Attributes toStudyAttributes(Long studyPk, ScrollableResults results) {
        Integer numberOfInstancesI = results.getInteger(3);
        int numberOfStudyRelatedInstances;
        int numberOfStudyRelatedSeries;
        String modalitiesInStudy;
        String sopClassesInStudy;
        if (numberOfInstancesI != null) {
            numberOfStudyRelatedInstances = numberOfInstancesI;
            numberOfStudyRelatedSeries = results.getInteger(4);
            modalitiesInStudy = results.getString(5);
            sopClassesInStudy = results.getString(6);
        } else {
            StudyQueryAttributes studyView = context.getQueryService()
                    .createStudyView(studyPk,  context.getQueryParam());
            numberOfStudyRelatedInstances = studyView.getNumberOfInstances();
            numberOfStudyRelatedSeries = studyView.getNumberOfSeries();
            modalitiesInStudy = studyView.getRawModalitiesInStudy();
            sopClassesInStudy = studyView.getRawSOPClassesInStudy();
        }

        byte[] studyByteAttributes = results.getBinary(11);
        byte[] patientByteAttributes = results.getBinary(12);
        Attributes patientAttrs = new Attributes();
        Attributes studyAttrs = new Attributes();
        Utils.decodeAttributes(patientAttrs, patientByteAttributes);
        Utils.decodeAttributes(studyAttrs, studyByteAttributes);
        Attributes attrs = Utils.mergeAndNormalize(patientAttrs, studyAttrs);
        Utils.setStudyQueryAttributes(attrs,
                numberOfStudyRelatedSeries,
                numberOfStudyRelatedInstances,
                modalitiesInStudy,
                sopClassesInStudy);

        return attrs;
    }
}
