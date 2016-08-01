/*
 * *** BEGIN LICENSE BLOCK *****
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
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2013
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
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.delete.impl;


import org.dcm4che3.data.Code;
import org.dcm4che3.net.Device;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.conf.RejectionNote;
import org.dcm4chee.arc.delete.*;
import org.dcm4chee.arc.entity.*;
import org.dcm4chee.arc.patient.PatientMgtContext;
import org.dcm4chee.arc.patient.PatientService;
import org.dcm4chee.arc.delete.StudyRetentionPolicyNotExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Nov 2015
 */
@ApplicationScoped
public class DeletionServiceImpl implements DeletionService {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionServiceImpl.class);

    @PersistenceContext(unitName = "dcm4chee-arc")
    private EntityManager em;

    @Inject
    private DeletionServiceEJB ejb;

    @Inject
    private PatientService patientService;

    @Inject
    private Device device;

    @Inject
    private Event<StudyDeleteContext> studyDeletedEvent;

    @Inject
    private Event<PatientMgtContext> patientMgtEvent;


    @Override
    public int deleteRejectedInstancesBefore(Code rjCode, Date before, int fetchSize) {
        return delete(before != null ? Location.FIND_BY_REJECTION_CODE_BEFORE : Location.FIND_BY_REJECTION_CODE,
                rjCode, before, fetchSize);
    }

    @Override
    public int deleteRejectionNotesBefore(Code rjCode, Date before, int fetchSize) {
        return delete(before != null ? Location.FIND_BY_CONCEPT_NAME_CODE_BEFORE : Location.FIND_BY_CONCEPT_NAME_CODE,
                rjCode, before, fetchSize);
    }

    private int delete(String queryName, Code rjCode, Date before, int fetchSize) {
        int total = 0;
        int deleted;
        do {
            total += deleted = ejb.deleteRejectedInstancesOrRejectionNotesBefore(queryName, rjCode, before, fetchSize);
        } while (deleted == fetchSize);
        return total;
    }

    @Override
    public StudyDeleteContext createStudyDeleteContext(String studyUID, HttpServletRequest request) {
        StudyDeleteContext ctx = new StudyDeleteContextImpl(null, studyUID);
        ctx.setHttpRequest(request);
        return ctx;
    }

    @Override
    public void deleteStudy(String studyUID, HttpServletRequest request)
            throws StudyNotFoundException, StudyNotEmptyException {
        StudyDeleteContext ctx = null;
        try {
            Study study = em.createNamedQuery(Study.FIND_BY_STUDY_IUID, Study.class)
                    .setParameter(1, studyUID).getSingleResult();
            if (study != null) {
                ctx = createStudyDeleteContext(study.getStudyInstanceUID(), request);
                ctx.setStudy(study);
                ctx.setPatient(study.getPatient());
                ctx.setDeletePatientOnDeleteLastStudy(false);
                if (study.getRejectionState() == RejectionState.COMPLETE)
                    ejb.removeStudyOnStorage(ctx);
                else if (study.getRejectionState() == RejectionState.EMPTY)
                    ejb.deleteEmptyStudy(ctx);
                else
                    throw new StudyNotEmptyException();
            }
            LOG.info("Successfully delete {} from database", ctx.getStudy());
        } catch (NoResultException e) {
            throw new StudyNotFoundException(e.getMessage());
        } catch (StudyNotEmptyException e) {
            ctx.setException(new StudyNotEmptyException("Study is not empty."));
            studyDeletedEvent.fire(ctx);
            throw new StudyNotEmptyException(ctx.getException().getMessage());
        } catch (Exception e) {
            LOG.warn("Failed to delete {} on {}", ctx.getStudy(), e);
            ctx.setException(e);
            studyDeletedEvent.fire(ctx);
        }
    }

    @Override
    public void deletePatient(PatientMgtContext ctx) throws StudyRetentionPolicyNotExpiredException, StudyNotFoundException{
        List<Study> sList = em.createNamedQuery(Study.FIND_BY_PATIENT, Study.class)
                .setParameter(1, ctx.getPatient()).getResultList();
        StudyDeleteContext studyDeleteCtx = null;
        if (!sList.isEmpty()) {
            try {
                for (Study study : sList) {
                    studyDeleteCtx = checkStudyRetentionPolicyNotExpired(study, ctx.getHttpRequest(), studyDeleteCtx, ctx);
                    if (studyDeleteCtx != null && studyDeleteCtx.getException() != null) {
                        throw new StudyRetentionPolicyNotExpiredException(studyDeleteCtx.getException().getMessage());
                    }
                }
            } catch(StudyRetentionPolicyNotExpiredException e) {
                studyDeletedEvent.fire(studyDeleteCtx);
                patientMgtEvent.fire(ctx);
                throw new StudyRetentionPolicyNotExpiredException(e.getMessage());
            }
        }
        boolean studiesRemoved;
        for (Study s : sList) {
            Long studyPk = s.getPk();
            String studyUID = s.getStudyInstanceUID();
            StudyDeleteContextImpl sCtx = new StudyDeleteContextImpl(studyPk, studyUID);
            sCtx.setDeletePatientOnDeleteLastStudy(false);
            sCtx.setHttpRequest(ctx.getHttpRequest());
            studiesRemoved = ejb.removeStudyOnStorage(sCtx);
            if(!studiesRemoved) {
                sCtx.setStudy(s);
                ejb.deleteEmptyStudy(sCtx);
            }
            else
                studyDeletedEvent.fire(sCtx);
        }
        patientService.deletePatientFromUI(ctx);
        LOG.info("Successfully delete {} from database", ctx.getPatient());
    }

    private StudyDeleteContext checkStudyRetentionPolicyNotExpired(
            Study study, HttpServletRequest request, StudyDeleteContext ctx, PatientMgtContext pCtx)
            throws StudyRetentionPolicyNotExpiredException {
        if (studyNotExpired(study)) {
            ctx = createStudyDeleteContext(study.getStudyInstanceUID(), request);
            ctx.setStudy(study);
            ctx.setPatient(study.getPatient());
            ctx.setException(new StudyRetentionPolicyNotExpiredException("Study retention policy for study has not expired."));
            if (pCtx != null)
                pCtx.setException(new StudyRetentionPolicyNotExpiredException(
                        "Study retention policy for study " + study.getStudyInstanceUID() + " of patient has not expired."));
        }
        return ctx;
    }

    private boolean studyNotExpired(Study study) {
        LocalDate now = LocalDate.now();
        LocalDate studyExpirationDate = study.getExpirationDate();
        return studyExpirationDate != null && studyExpirationDate.isAfter(now);
    }
}
