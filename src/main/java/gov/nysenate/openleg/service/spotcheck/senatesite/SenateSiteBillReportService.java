package gov.nysenate.openleg.service.spotcheck.senatesite;

import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.bill.data.BillUpdatesDao;
import gov.nysenate.openleg.dao.bill.reference.senatesite.SenateSiteBillDao;
import gov.nysenate.openleg.dao.spotcheck.BillIdSpotCheckReportDao;
import gov.nysenate.openleg.dao.spotcheck.SpotCheckReportDao;
import gov.nysenate.openleg.model.bill.BaseBillId;
import gov.nysenate.openleg.model.bill.Bill;
import gov.nysenate.openleg.model.bill.BillId;
import gov.nysenate.openleg.model.spotcheck.*;
import gov.nysenate.openleg.model.spotcheck.senatesite.SenateSiteBill;
import gov.nysenate.openleg.model.spotcheck.senatesite.SenateSiteBillDump;
import gov.nysenate.openleg.model.updates.UpdateToken;
import gov.nysenate.openleg.model.updates.UpdateType;
import gov.nysenate.openleg.service.bill.data.BillDataService;
import gov.nysenate.openleg.service.bill.data.BillNotFoundEx;
import gov.nysenate.openleg.service.spotcheck.base.BaseSpotCheckReportService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SenateSiteBillReportService extends BaseSpotCheckReportService<BillId> {

    private static final Logger logger = LoggerFactory.getLogger(SenateSiteBillReportService.class);

    @Autowired private BillIdSpotCheckReportDao billReportDao;
    @Autowired private SenateSiteBillDao senateSiteBillDao;
    @Autowired private SenateSiteBillJsonParser billJsonParser;

    @Autowired private BillDataService billDataService;
    @Autowired private BillUpdatesDao billUpdatesDao;

    @Autowired private SenateSiteBillCheckService billCheckService;

    @Override
    protected SpotCheckReportDao<BillId> getReportDao() {
        return billReportDao;
    }

    @Override
    public SpotCheckRefType getSpotcheckRefType() {
        return SpotCheckRefType.SENATE_SITE_BILLS;
    }

    @Override
    public synchronized SpotCheckReport<BillId> generateReport(LocalDateTime start, LocalDateTime end) throws Exception {
        SenateSiteBillDump billDump = getMostRecentDump();
        SpotCheckReportId reportId = new SpotCheckReportId(SpotCheckRefType.SENATE_SITE_BILLS,
                billDump.getBillDumpId().getToDateTime(), LocalDateTime.now());
        SpotCheckReport<BillId> report = new SpotCheckReport<>(reportId);
        try {

            logger.info("getting bill updates");

            // Get reference bills using the bill dump update interval
            Set<BaseBillId> updatedBillIds = getBillUpdatesDuring(billDump);
            logger.info("got {} updated bill ids", updatedBillIds.size());
            Map<BaseBillId, Bill> updatedBills = new LinkedHashMap<>();
            logger.info("retrieving bills");
            for (BaseBillId billId : updatedBillIds) {
                try {
                    updatedBills.put(billId, billDataService.getBill(billId));
                } catch (BillNotFoundEx ex) {
                    SpotCheckObservation<BillId> observation = new SpotCheckObservation<>(reportId.getReferenceId(), billId);
                    observation.addMismatch(new SpotCheckMismatch(SpotCheckMismatchType.OBSERVE_DATA_MISSING, "", ""));
                    report.addObservation(observation);
                }
            }
            logger.info("got {} bills", updatedBills.size());
            logger.info("retrieving bill dump");
            // Extract senate site bills from the dump, filtering out those that have received updates since the dump
            // Ignored bills are added to the report notes
            List<SenateSiteBill> dumpedBills = getDumpedBills(billDump, updatedBills, report);
            logger.info("parsed {} dumped bills", dumpedBills.size());
            logger.info("archiving bill dump...");

            logger.info("comparing bills present");
            // Add observations for any missing bills that should have been in the dump
            report.addObservations(getRefDataMissingObs(dumpedBills, updatedBills.values(), reportId.getReferenceId()));

            logger.info("checking bills");
            // Check each dumped senate site bill
            dumpedBills.stream()
                    .map(senSiteBill -> billCheckService.check(updatedBills.get(senSiteBill.getBaseBillId()), senSiteBill))
                    .forEach(report::addObservation);

            logger.info("done: {} mismatches", report.getOpenMismatchCount(false));
        } finally {
            senateSiteBillDao.setProcessed(billDump);
        }
        return report;
    }

    /** --- Internal Methods --- */

    private SenateSiteBillDump getMostRecentDump() throws IOException, ReferenceDataNotFoundEx {
        return senateSiteBillDao.getPendingDumps().stream()
                .filter(SenateSiteBillDump::isComplete)
                .max(SenateSiteBillDump::compareTo)
                .orElseThrow(() -> new ReferenceDataNotFoundEx("Found no full senate site bill dumps"));
    }

    /**
     * Gets a set of bill ids that were updated during the update interval specified by the bill dump
     * Bills that were updated after the end of this interval are excluded, even if they may have been included in the dump
     *
     * @param billDump SenateSiteBillDump
     * @return Set<Bill>
     */
    private Set<BaseBillId> getBillUpdatesDuring(SenateSiteBillDump billDump) {
        Range<LocalDateTime> dumpUpdateInterval = billDump.getBillDumpId().getUpdateInterval();
        return billUpdatesDao.getUpdates(Range.greaterThan(billDump.getBillDumpId().getFromDateTime()),
                UpdateType.PROCESSED_DATE, null, SortOrder.ASC, LimitOffset.ALL)
                .getResults().stream()
                .filter(token -> dumpUpdateInterval.contains(token.getProcessedDateTime()))
                .map(UpdateToken::getId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Extract senate site bills from the given senate site bill dump
     * filter out any senate site bills that were updated on openleg after the dump interval
     * (these bills wont be present in the updatedBills map passed in)
     * filtered out bills are recorded in the report notes
     * @param billDump SenateSiteBillDump
     * @param updatedBills Map<BaseBillId, Bill> - bills whose latest update was in the bill dump interval
     * @return List<SenateSiteBill>
     */
    private List<SenateSiteBill> getDumpedBills(SenateSiteBillDump billDump, Map<BaseBillId, Bill> updatedBills,
                                                SpotCheckReport<BillId> report) {
        List<SenateSiteBill> includedBills = new LinkedList<>();
        TreeSet<BaseBillId> ignoredBills = new TreeSet<>();
        billJsonParser.parseBills(billDump).forEach(senSiteBill -> {
            if (updatedBills.containsKey(senSiteBill.getBaseBillId())) {
                includedBills.add(senSiteBill);
            } else {
                ignoredBills.add(senSiteBill.getBaseBillId());
            }
        });
        if (!ignoredBills.isEmpty()) {
            report.setNotes("ignored: " + StringUtils.join(ignoredBills, ", "));
        }
        return includedBills;
    }

    /**
     * Generate data missing observations for all bills that were updated in the bill dump update interval,
     *  but not included in the bill dump
     * @param senSiteBills Collection<SenateSiteBill> - Bills extracted from the dump
     * @param openlegBills Collection<Bill> - Bills updated during the dump interval
     * @param refId SpotCheckReferenceId - reference Id used to create the observations
     * @return List<SpotCheckObservation<BillId>>
     */
    private List<SpotCheckObservation<BillId>> getRefDataMissingObs(Collection<SenateSiteBill> senSiteBills,
                                                                    Collection<Bill> openlegBills,
                                                                    SpotCheckReferenceId refId) {
        Set<BillId> senSiteBillIds = senSiteBills.stream()
                .map(SenateSiteBill::getBillId)
                .collect(Collectors.toSet());
        Set<BillId> openlegBillIds = openlegBills.stream()
                .flatMap(bill -> bill.getAmendmentIds().stream())
                .collect(Collectors.toSet());
        return Sets.difference(openlegBillIds, senSiteBillIds).stream()
                .map(billId -> {
                    SpotCheckObservation<BillId> observation = new SpotCheckObservation<>(refId, billId);
                    observation.addMismatch(new SpotCheckMismatch(SpotCheckMismatchType.REFERENCE_DATA_MISSING, "", ""));
                    return observation;
                })
                .collect(Collectors.toList());
    }
}
