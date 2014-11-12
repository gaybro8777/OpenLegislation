package gov.nysenate.openleg.controller.api.report;

import gov.nysenate.openleg.client.response.base.BaseResponse;
import gov.nysenate.openleg.client.response.error.ErrorCode;
import gov.nysenate.openleg.client.response.error.ErrorResponse;
import gov.nysenate.openleg.client.response.error.ViewObjectErrorResponse;
import gov.nysenate.openleg.client.response.spotcheck.ReportDetailResponse;
import gov.nysenate.openleg.client.response.spotcheck.ReportSummaryResponse;
import gov.nysenate.openleg.client.view.base.ListView;
import gov.nysenate.openleg.client.view.spotcheck.ReportIdView;
import gov.nysenate.openleg.client.view.spotcheck.ReportInfoView;
import gov.nysenate.openleg.controller.api.base.BaseCtrl;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.model.bill.BaseBillId;
import gov.nysenate.openleg.model.spotcheck.SpotCheckRefType;
import gov.nysenate.openleg.model.spotcheck.SpotCheckReport;
import gov.nysenate.openleg.model.spotcheck.SpotCheckReportId;
import gov.nysenate.openleg.model.spotcheck.SpotCheckReportNotFoundEx;
import gov.nysenate.openleg.service.spotcheck.DaybreakCheckReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static gov.nysenate.openleg.controller.api.base.BaseCtrl.BASE_API_PATH;
import static gov.nysenate.openleg.util.DateUtils.atEndOfDay;
import static java.util.stream.Collectors.toList;
import static org.springframework.format.annotation.DateTimeFormat.ISO;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequestMapping(value = BASE_API_PATH + "/spotcheck", method = RequestMethod.GET)
public class SpotCheckGetCtrl extends BaseCtrl
{
    private static final Logger logger = LoggerFactory.getLogger(SpotCheckGetCtrl.class);

    @Autowired
    DaybreakCheckReportService daybreakService;

    /**
     *
     *
     */
    @RequestMapping(value = "/daybreaks", produces = APPLICATION_JSON_VALUE)
    public BaseResponse getDaybreakReport() {
        LocalDate today = LocalDate.now();
        LocalDate sixMonthsAgo = today.minusMonths(6);
        return getDaybreakReportLimOff(sixMonthsAgo, today);
    }

    /**
     *
     *
     */
    @RequestMapping(value = "/daybreaks/{from}/{to}", produces = APPLICATION_JSON_VALUE)
    public BaseResponse getDaybreakReportLimOff(
           @PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate from,
           @PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        logger.info("Retrieving daybreak reports from {} to {}", from , to);
        // Have to retrieve the reports in full, no 'summary' view available
        List<SpotCheckReport<BaseBillId>> reports =
                daybreakService.getReportIds(from.atStartOfDay(), atEndOfDay(to), SortOrder.DESC, LimitOffset.ALL)
                    .parallelStream()
                    .map(daybreakService::getReport).collect(toList());
        // Construct the client response
        return new ReportSummaryResponse<>(
            ListView.of(reports.stream().map(r -> new ReportInfoView<>(r)).collect(Collectors.toList())), from, to);
    }

    /**
     *
     */
    @RequestMapping(value = "/daybreaks/{reportDateTime}", produces = APPLICATION_JSON_VALUE)
    public BaseResponse getDaybreakReport(
           @PathVariable @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime reportDateTime) {
        logger.info("Retrieving daybreak report {}", reportDateTime);
        return new ReportDetailResponse<>(
            daybreakService.getReport(new SpotCheckReportId(SpotCheckRefType.LBDC_DAYBREAK, reportDateTime)));
    }

    /** --- Exception Handlers --- */

    @ExceptionHandler(SpotCheckReportNotFoundEx.class)
    @ResponseStatus(value = HttpStatus.NOT_FOUND)
    public ErrorResponse handleSpotCheckReportNotFoundEx(SpotCheckReportNotFoundEx ex) {
        return new ViewObjectErrorResponse(ErrorCode.SPOTCHECK_REPORT_NOT_FOUND, new ReportIdView(ex.getReportId()));
    }
}