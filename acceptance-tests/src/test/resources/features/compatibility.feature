Feature: Java 25 and Cucumber compatibility
  Scenario: Exact elapsed reservation and adjacency
    Given a reservation beginning at "2027-03-28T00:30:00Z"
    Then it reserves exactly 120 elapsed minutes
    And a following reservation is adjacent
