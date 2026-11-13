package br.com.autocuidado.agenda;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

public final class DailyMessageCatalog {

    public static final int CURRENT_VERSION = 1;

    // The contents, indices and shuffle for a saved version must stay stable.
    private static final List<String> V1 = List.of(
            "Lembre-se de se cuidar.",
            "Vá no seu ritmo.",
            "Seu cuidado pode ser simples.",
            "Faça uma pausa para você.",
            "Um pequeno cuidado já conta.",
            "Hoje pode ser dia de descanso.",
            "Dê espaço para o que cabe hoje.",
            "Que tal separar um momento seu?",
            "Reagendar também faz parte.",
            "Escolha o que faz sentido para você.",
            "Uma rotina boa cabe no seu dia.",
            "Cuide de você com gentileza.",
            "Perceba o que te faz sentir bem.",
            "Você pode começar pelo mais fácil.",
            "Faça só o que couber na sua rotina.",
            "Abra espaço para o que te faz bem.",
            "Deixe a rotina caber na sua vida.",
            "Ajuste o plano se o dia mudou.",
            "Escolha um cuidado que você goste.",
            "Seu tempo também merece atenção.",
            "Recomece quando fizer sentido.",
            "Um dia de cada vez está ótimo.",
            "Cuidado também pode ser leve.",
            "Aproveite o cuidado do seu jeito.",
            "Guarde um momento tranquilo para você.",
            "Escolha uma etapa e vá com calma.",
            "Lembre-se do protetor solar na rotina do dia.",
            "Hoje é uma nova oportunidade de se cuidar.",
            "Registre o que você já fez, se quiser.",
            "Descanso também faz parte da rotina.");

    private DailyMessageCatalog() {}

    public static Message forDate(int version, long seed, LocalDate startedOn, LocalDate today) {
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported daily message catalog version: " + version);
        }
        int size = V1.size();
        long elapsed = Math.max(0, ChronoUnit.DAYS.between(startedOn, today));
        long cycle = elapsed / size;
        int position = (int) (elapsed % size);
        int[] order = shuffledV1(seed, cycle);
        // Only the first two positions can change, so the prior cycle's last position needs no correction.
        if (cycle > 0 && position < 2 && order[0] == shuffledV1(seed, cycle - 1)[size - 1]) {
            position = 1 - position;
        }
        int index = order[position];
        return new Message(index + 1, V1.get(index), index == 26 ? "SKIN" : null);
    }

    private static int[] shuffledV1(long seed, long cycle) {
        int[] order = IntStream.range(0, V1.size()).toArray();
        var random = new Random(seed + cycle * 0x9E3779B97F4A7C15L);
        for (int i = order.length - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            int value = order[i];
            order[i] = order[other];
            order[other] = value;
        }
        return order;
    }

    public record Message(int number, String text, String category) {}
}
