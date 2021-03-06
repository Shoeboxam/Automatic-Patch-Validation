package com.hearthsim.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.function.Predicate;

import com.hearthsim.card.Card;
import com.hearthsim.card.Deck;
import com.hearthsim.card.ImplementedCardList;
import com.hearthsim.card.ImplementedCardList.ImplementedCard;

/**
 * This class provides a mechanism for generating random decks.
 *
 * @author dyllonmgagnier
 *
 */
public class DeckFactory {
    private ArrayList<ImplementedCard> cards;
    private boolean limitCopies;
    private Random gen;

    /**
     * This method initializes a new DeckFactory.
     *
     * @param filter
     *            Any card for which this returns true will be removed from the
     *            potential card pool.
     * @param limitCopies
     *            If true, then any deck will contain no more than two copies of
     *            any card no more than one copy of any legendary.
     */
    protected DeckFactory(Predicate<ImplementedCard> filter, boolean limitCopies) {
        cards = ImplementedCardList.getInstance().getCardList();
        cards.removeIf(filter);
        gen = new Random();
    }
    
    /**
     * 
     * @return All possible cards that could be in a deck generated by this class as specified by the DeckFactoryBuilder.
     */
    public ArrayList<ImplementedCard> getAllPossibleCards()
    {
    	return new ArrayList<ImplementedCard>(cards);
    }

    /**
     * This method generates a new random deck as specified by the builder. The
     * decks are completely random so shuffling is unnecessary.
     *
     * @return
     */
    public Deck generateRandomDeck() {
        Card[] result = new Card[30];
        if (limitCopies) {
            HashMap<ImplementedCard, Integer> cardsInDeck = new HashMap<ImplementedCard, Integer>();
            for (int i = 0; i < 30; i++) {
                ImplementedCard toAdd;
                // Keep going until a card is found that can be added to the
                // deck.
                while (true) {
                    toAdd = cards.get(gen.nextInt(cards.size()));
                    if (!cardsInDeck.containsKey(toAdd)) {
                        cardsInDeck.put(toAdd, 1);
                        break;
                    } else if (cardsInDeck.get(toAdd).equals(1)
                            && !toAdd.rarity_.equals("legendary")) {
                        cardsInDeck.put(toAdd, 2);
                        break;
                    }
                }
                result[i] = toAdd.createCardInstance();
            }
        } else {
            for (int i = 0; i < 30; i++) {
                result[i] = cards.get(gen.nextInt(cards.size()))
                        .createCardInstance();
            }
        }

        return new Deck(result);
    }

    /**
     * This class builds a DeckFactory and allows for various options to be
     * selected for the factory.
     *
     * @author dyllonmgagnier
     *
     */
    public static class DeckFactoryBuilder {
        private Predicate<ImplementedCard> filter;
        private boolean limitCopies;
        private boolean allowUncollectible;

        /**
         * Constructs the default builder which does not allow for uncollectible
         * cards and will limit the number of copies of any card to no more than
         * two and limits the number of copies any particular legendar to no
         * more than one.
         */
        public DeckFactoryBuilder() {
            filter = (card) -> false;
            limitCopies = true;
            allowUncollectible = false;
        }

        /**
         * Limits the the card pool to only those specified by the given
         * rarities.
         *
         * @param rarities
         */
        public void filterByRarity(String... rarities) {
            filter = filter.or((card) -> {
                boolean result = true;
                for (String rarity : rarities)
                    result = result && !card.rarity_.equals(rarity);
                return result;
            });
        }

        /**
         * Only select cards usable by the input character class (i.e. warlock,
         * priest, mage, rogue, etc.).  As a note, if neutral cards are also desired, "neutral" must be
         * included as an argument.
         *
         * @param characterClass
         *            The classes to filter by.
         */
        public void filterByHero(String ... characterClasses) {
            filter = filter.or((card) -> 
            {
            	boolean result = true;
            	for(String characterClass : characterClasses)
            		result = result && !card.charClass_.equals(characterClass);
            	return result;
            });
        }

        /**
         * This method allows for uncollectible cards to be in the card pool.
         */
        public void allowUncollectible() {
            allowUncollectible = true;
        }

        /**
         * This method generates a DeckFactory based on the previously selected
         * options.
         *
         * @return A DeckFactory limited by the various options.
         */
        public DeckFactory buildDeckFactory() {
            if (!allowUncollectible)
                filter = filter.or((card) -> !card.collectible);
            return new DeckFactory(filter, limitCopies);
        }

        /**
         * This method only allows for cards between the minimum and maximum
         * mana cost.
         *
         * @param minimumCost
         *            The minimum mana cost allowed.
         * @param maximumCost
         *            The maximum mana cost allowed.
         */
        public void filterByManaCost(int minimumCost, int maximumCost) {
            filter = filter.or((card) -> card.mana_ < minimumCost
                    || card.mana_ > maximumCost);
        }

        /**
         * This method allows for unlimited copies of cards to be used (i.e.
         * like in Arena).
         */
        public void allowUnlimitedCopiesOfCards() {
            limitCopies = false;
        }
    }
}
