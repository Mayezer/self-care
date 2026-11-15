package br.com.autocuidado.hair;

import java.time.LocalDate;

import tools.jackson.databind.JsonNode;

import static br.com.autocuidado.hair.HairStage.WASH_ONLY;

enum HairScheduleModel {
    ORGANIZE("organize-washes", WASH_ONLY, "Você escolheu organizar os dias de lavagem."),
    HYDRATION("include-hydration", HairStage.HYDRATION, "Você escolheu incluir uma sessão de hidratação."),
    NUTRITION("include-nutrition", HairStage.NUTRITION, "Você escolheu incluir uma sessão de nutrição."),
    RECONSTRUCTION("include-reconstruction", HairStage.RECONSTRUCTION, "Você escolheu incluir uma sessão de reconstrução.");

    private static final int VERSION = 1;
    private static final LocalDate REVIEWED_ON = LocalDate.of(2026, 11, 13);

    private final String id;
    private final HairStage firstStage;
    private final String reason;

    HairScheduleModel(String id, HairStage firstStage, String reason) {
        this.id = id;
        this.firstStage = firstStage;
        this.reason = reason;
    }

    static Selection select(HairScheduleModel goal, JsonNode profile) {
        if (goal != null) {
            return goal.selected(goal.reason);
        }
        for (JsonNode observation : profile.path("observations")) {
            String answer = observation.asString("");
            if ("NONE".equals(answer)) {
                return ORGANIZE.selected("Você marcou ‘Nenhum desses’ em Meu cabelo. A prévia organiza seus dias de lavagem.");
            }
            if (!"UNKNOWN".equals(answer)) {
                return new Selection(WASH_ONLY, new Explanation("NO_REVIEWED_MODEL", null, null, null,
                        "Ainda não há um modelo revisado para essas observações. Você pode escolher um objetivo ou editar a prévia de lavagem."));
            }
        }
        return new Selection(WASH_ONLY, new Explanation("INSUFFICIENT_ANSWERS", null, null, null,
                "Escolha um objetivo para receber uma sugestão ou comece com a prévia de lavagem."));
    }

    private Selection selected(String reason) {
        return new Selection(firstStage, new Explanation("MODEL_SELECTED", id, VERSION, REVIEWED_ON, reason));
    }

    record Selection(HairStage firstStage, Explanation explanation) {
        HairStage stageAt(int index) {
            return index == 0 ? firstStage : WASH_ONLY;
        }
    }

    record Explanation(String status, String modelId, Integer version, LocalDate reviewedOn, String reason) {}
}
