package br.com.autocuidado.hair;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

public record HairCareRoutine(String modelId, int version, LocalDate reviewedOn, boolean personalized,
                              String summary, List<Step> steps, List<String> notes) {

    public static HairCareRoutine from(JsonNode profile) {
        var groups = new HashSet<String>();
        for (var group : profile.path("curlPattern").path("groups")) groups.add(group.path("group").asString(""));
        boolean curly = !groups.isEmpty() && Set.of("CURLY", "COILY").containsAll(groups);
        boolean straight = groups.equals(Set.of("STRAIGHT"));
        boolean fine = "FINE".equals(profile.path("strandThickness").asString(""));
        boolean thick = "THICK".equals(profile.path("strandThickness").asString(""));
        boolean longHair = "BELOW_SHOULDERS".equals(profile.path("visualLength").asString(""));
        boolean dry = contains(profile, "observations", "DRYNESS");
        boolean oily = "OFTEN".equals(profile.path("rootOiliness").asString(""));
        boolean chemical = containsAny(profile, "chemicalTreatments", Set.of("DYE", "BLEACH", "HIGHLIGHTS", "STRAIGHTENING_OR_RELAXING", "OTHER"));
        String heatFrequency = profile.path("heatFrequency").asString("");
        boolean heat = Set.of("LESS_THAN_WEEKLY", "ONE_TO_THREE_PER_WEEK", "FOUR_OR_MORE_PER_WEEK").contains(heatFrequency)
                || (!"NEVER".equals(heatFrequency) && !profile.path("heatTools").isEmpty());

        var reasons = new ArrayList<String>();
        if (curly) reasons.add("cachos ou crespos");
        else if (straight) reasons.add("fios lisos");
        if (fine) reasons.add("espessura fina");
        if (thick) reasons.add("espessura grossa");
        if (longHair) reasons.add("comprimento abaixo dos ombros");
        if (dry) reasons.add("ressecamento relatado");
        if (contains(profile, "observations", "FRIZZ")) reasons.add("frizz relatado");
        if (contains(profile, "observations", "BREAKAGE")) reasons.add("quebra relatada");
        if (oily) reasons.add("oleosidade frequente na raiz");
        if (chemical) reasons.add("química informada");
        if (heat) reasons.add("uso de calor");

        var steps = new ArrayList<Step>();
        steps.add(new Step("cleanse", "Lavar com suavidade", "Aplique o xampu no couro cabeludo e massageie suavemente. Enxágue sem esfregar o comprimento."));
        String conditioning = fine || straight
                ? "Comece o condicionador pelas pontas, sem concentrá-lo na raiz."
                : "Depois da lavagem, distribua o condicionador com suavidade pelo comprimento e pelas pontas.";
        if (fine && curly) conditioning = "Comece o condicionador pelas pontas e alcance o comprimento que precisa de condicionamento.";
        if (curly) conditioning += " Mantenha os fios condicionados para desembaraçar antes de enxaguar.";
        else conditioning += " Enxágue depois de condicionar.";
        steps.add(new Step("condition", "Condicionar os fios", conditioning));
        String detangling = curly
                ? "Desembarace os fios úmidos, com condicionador, usando pente de dentes largos. Comece pelas pontas e enxágue depois. Evite escovar os cachos secos."
                : straight
                ? "Deixe os fios secarem um pouco antes de pentear. Desembarace devagar, com pente de dentes largos, começando pelas pontas."
                : "Desembarace devagar, começando pelas pontas, com pente de dentes largos e sem puxar os fios.";
        if (curly || thick || longHair) {
            detangling += " Divida o cabelo em pequenas seções para facilitar o manuseio.";
        }
        String drying = "Absorva a água com uma toalha ou camiseta, sem esfregar. Deixe secar naturalmente quando possível.";
        if (!curly) steps.add(new Step("dry", "Retirar o excesso de água", drying));
        steps.add(new Step("detangle", "Desembaraçar sem tração", detangling));
        if (curly) steps.add(new Step("dry", "Retirar o excesso de água", drying));
        if (heat) steps.add(new Step("heat", "Se usar uma ferramenta de calor", "Reduza a exposição e prefira ajuste baixo ou médio. Use proteção térmica indicada para a ferramenta; o uso de calor não é uma etapa obrigatória desta sessão."));

        var notes = new ArrayList<String>();
        if (oily) notes.add("Ajuste as lavagens à oleosidade e à sujeira percebidas. Reorganize os dias da agenda se precisar; a oleosidade da raiz não elimina o condicionamento das pontas.");
        if (dry) notes.add("Como você relatou ressecamento, dê atenção ao condicionamento e reduza o atrito ao manusear os fios.");
        if (contains(profile, "observations", "FRIZZ")) notes.add("O condicionamento e o desembaraço cuidadoso podem facilitar o manejo do frizz. Frizz, sozinho, não determina uma etapa de nutrição.");
        if (chemical) notes.add("Priorize condicionamento e manuseio gentil. Antes de repetir ou combinar procedimentos químicos, procure orientação profissional. O mês informado não determina a recuperação dos fios.");
        if (contains(profile, "observations", "BREAKAGE")) notes.add("Evite puxar os fios e reduza calor e atrito. Se a quebra for intensa ou persistente, procure dermatologista; quebra não comprova falta de queratina.");
        boolean personalized = !reasons.isEmpty();
        String summary = personalized
                ? "Rotina adaptada às respostas salvas: " + String.join(", ", reasons) + ". Os cuidados abaixo valem para cada data do seu ciclo."
                : "Esta é uma rotina geral de cuidados em cada lavagem. Preencha Meu cabelo para adaptar o manuseio, o condicionamento e as orientações à sua rotina.";
        return new HairCareRoutine("daily-hair-care", 2, LocalDate.of(2026, 11, 23), personalized, summary, List.copyOf(steps), List.copyOf(notes));
    }

    private static boolean contains(JsonNode profile, String field, String value) {
        return containsAny(profile, field, Set.of(value));
    }

    private static boolean containsAny(JsonNode profile, String field, Set<String> values) {
        for (var value : profile.path(field)) if (values.contains(value.asString(""))) return true;
        return false;
    }

    public record Step(String id, String title, String instruction) {}
}
