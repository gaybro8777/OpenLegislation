package gov.nysenate.openleg.client.view.agenda;

import com.fasterxml.jackson.annotation.JsonIgnore;
import gov.nysenate.openleg.client.view.base.ListView;
import gov.nysenate.openleg.client.view.base.ViewObject;
import gov.nysenate.openleg.model.agenda.AgendaId;
import gov.nysenate.openleg.model.agenda.AgendaInfoCommittee;
import gov.nysenate.openleg.model.agenda.AgendaVoteCommittee;
import gov.nysenate.openleg.model.agenda.CommitteeAgendaAddendumId;
import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.entity.CommitteeId;
import gov.nysenate.openleg.service.bill.data.BillDataService;

import java.time.LocalDateTime;

import static java.util.stream.Collectors.toList;

public class AgendaCommAddendumView implements ViewObject
{
    private String addendumId;
    private LocalDateTime modifiedDateTime;
    private boolean hasVotes = false;
    private AgendaMeetingView meeting;
    private ListView<AgendaItemView> bills;
    private AgendaVoteView voteInfo;
    private AgendaId agendaId;
    private CommitteeId committeeId;

    public AgendaCommAddendumView(String addendumId, LocalDateTime modDateTime, AgendaInfoCommittee infoComm,
                                  AgendaVoteCommittee voteComm, BillDataService billDataService) {
        this.addendumId = addendumId;
        if (infoComm != null) {
            this.modifiedDateTime = modDateTime;
            this.meeting = new AgendaMeetingView(infoComm.getChair(), infoComm.getLocation(),
                                                 infoComm.getMeetingDateTime(), infoComm.getNotes());
            this.bills = ListView.of(infoComm.getItems().stream()
                .map(i -> new AgendaItemView(i, billDataService))
                .collect(toList()));
            this.hasVotes = voteComm != null;
            if (this.hasVotes) {
                this.voteInfo = new AgendaVoteView(voteComm);
            }
            this.agendaId = infoComm.getAgendaId();
            this.committeeId = infoComm.getCommitteeId();
        }
    }

    //Added for Json Deserialization
    public AgendaCommAddendumView() {}

    public String getAddendumId() {
        return addendumId;
    }

    public LocalDateTime getModifiedDateTime() {
        return modifiedDateTime;
    }

    public boolean isHasVotes() {
        return hasVotes;
    }

    public AgendaMeetingView getMeeting() {
        return meeting;
    }

    public ListView<AgendaItemView> getBills() {
        return bills;
    }

    public AgendaVoteView getVoteInfo() {
        return voteInfo;
    }

    public AgendaId getAgendaId() {
        return agendaId;
    }

    public void setAgendaId(AgendaId agendaId) {
        this.agendaId = agendaId;
    }

    public CommitteeId getCommitteeId() {
        return committeeId;
    }

    public void setCommitteeId(CommitteeId committeeId) {
        this.committeeId = committeeId;
    }

    public CommitteeAgendaAddendumId getCommitteeAgendaAddendumId() {
        return new CommitteeAgendaAddendumId(this.agendaId, this.committeeId, Version.of(this.addendumId) );
    }

    @Override
    public String getViewType() {
        return "agenda-addendum";
    }

    //Added for Json Deserialization
    public void setModifiedDateTime(String modifiedDateTime) {this.modifiedDateTime = LocalDateTime.parse(modifiedDateTime);}
}
