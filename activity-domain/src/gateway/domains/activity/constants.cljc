(ns gateway.domains.activity.constants
  (:require [gateway.reason :refer [reason]]))

(defonce activity-domain-uri "activity")
(defonce failure (str activity-domain-uri ".errors.failure"))

(defonce activity-registration-failure (str activity-domain-uri ".errors.registration.failure"))
(defonce activity-missing-factory (str activity-domain-uri ".errors.missing_factory"))
(defonce activity-factory-already-registered (str activity-domain-uri ".errors.factory_already_registered"))
(defonce activity-request-cancelled (str activity-domain-uri ".request_cancelled"))
(defonce activity-missing-activity-type (str activity-domain-uri ".errors.missing_type"))
(defonce activity-owner-creation-failed (str activity-domain-uri ".errors.owner_creation"))
(defonce activity-invalid (str activity-domain-uri ".errors.invalid_activity"))
(defonce activity-already-in (str activity-domain-uri ".errors.already_in_activity"))
(defonce activity-not-a-member (str activity-domain-uri ".errors.not_a_member"))
(defonce activity-not-an-owner (str activity-domain-uri ".errors.not_an_owner"))
(defonce activity-is-child (str activity-domain-uri ".errors.activity_is_child"))
(defonce activity-bad-configuration (str activity-domain-uri ".errors.activity_bad_configuration"))
(defonce activity-not-authorized (str activity-domain-uri ".errors.not_authorized"))
(defonce activity-invalid-peer (str activity-domain-uri ".errors.invalid_peer"))
(defonce activity-unhandled-message (str activity-domain-uri ".errors.unhandled_message"))

(defonce reason-peer-removed (reason (str activity-domain-uri ".peer-removed") "Peer has been removed"))
(defonce reason-activity-owner-left (reason (str activity-domain-uri ".owner_left") "Activity owner left"))
(defonce reason-activity-destroyed (reason (str activity-domain-uri ".destroyed") "Activity destroyed"))
(defonce reason-activity-merged (reason (str activity-domain-uri ".merged") "Activity merged"))
(defonce reason-activity-split (reason (str activity-domain-uri ".split") "Activity split"))




